package app.strategy;

import app.common.Direction;
import app.displacement.DisplacementEvent;
import app.model.AnalysisResult;
import app.model.TradePlan;
import app.model.Zone;
import app.regime.MarketRegime;
import app.structure.StructureEvent;

import java.util.ArrayList;
import java.util.List;

public final class StrategyEngine {
    private StrategyEngine() {
    }

    public static List<StrategySignal> evaluateAll(AnalysisResult result) {
        if (result == null) return List.of(empty("no-chart", "Нет графика",
                "Сначала загрузи монету и таймфрейм."));
        return List.of(
                radarEntry(result),
                continuation(result),
                sweepReversal(result),
                ifvgBreaker(result),
                liquidityRun(result),
                oteReversion(result),
                bosPullback(result),
                propulsionBlock(result),
                mitigationRetest(result),
                orderFlowDelta(result),
                derivativesSqueeze(result),
                orderBookWallBounce(result),
                killzoneSweep(result),
                equilibriumScalp(result)
        );
    }

    public static StrategySignal evaluate(String id, AnalysisResult result) {
        for (StrategySignal signal : evaluateAll(result)) {
            if (signal.id.equals(id)) return signal;
        }
        return empty("unknown", "Стратегия не найдена", "Открой список стратегий заново.");
    }

    private static StrategySignal radarEntry(AnalysisResult r) {
        TradePlan p = r.plan;
        Score s = new Score(r, "smc-radar-entry",
                "SMC Radar сигнал",
                "Базовый SMC сетап по совокупности факторов: HTF-направление, sweep ликвидности, " +
                "структурный сдвиг, displacement, зона, объём. " +
                "Подходит для любой пары, найденной Радаром с 4+ факторами.");
        if (!hasPlan(s, p)) return s.build(false);
        direction(s, p);
        Zone z = p.zone;
        boolean htfAligned  = p.longSide == r.htfBullish;
        boolean hasSweep    = p.sweepIndex >= 0
                || (z != null && z.sweepConfirmed)
                || r.sweeps.stream().anyMatch(x ->
                        x.reversalDirection == (p.longSide ? Direction.BULLISH : Direction.BEARISH)
                        && x.score >= 55);
        boolean hasStruct   = p.chochIndex >= 0
                || !r.structureEvents.isEmpty()
                || (z != null && z.structureScore >= 52);
        boolean hasDisp     = z != null && z.displacementScore >= 55;
        boolean hasZone     = z != null && z.score >= 65 && !z.mitigated;
        boolean hasVolume   = z != null && z.volumeScore >= 40;
        boolean hasImbalance = r.imbalanceContext != null && r.imbalanceContext.supports(p.longSide);
        boolean hasDeviation = r.deviationContext  != null && r.deviationContext.supports(p.longSide);
        boolean rrOk        = p.rr2() >= 1.4;

        require(s, p.entryConfirmed, 16, "вход подтвержден (sweep+CHoCH+митигация)", "вход не подтвержден");
        require(s, htfAligned,       16, "HTF направление совпадает с входом",        "HTF против сделки");
        require(s, hasSweep,         14, "sweep ликвидности подтвержден",              "нет sweep ликвидности");
        require(s, hasStruct,        12, "структурный сдвиг BOS/CHoCH",                "нет CHoCH/BOS");
        require(s, hasDisp,          12, "displacement зафиксирован (55+)",             "нет значимого displacement");
        require(s, hasZone,          10, "зона качественная (score ≥65, не митигирована)", "зона слабая или митигирована");
        require(s, hasVolume,         8, "объём подтверждает зону",                     "объём не подтверждает зону");
        require(s, hasImbalance || hasDeviation, 8, "imbalance/deviation поддерживают", "нет imbalance/deviation");
        require(s, !chop(r),          6, "рынок не choppy",                             "рынок в боковике");
        require(s, noEventBlock(r),   6, "event risk в норме",                          "event/news риск");
        require(s, rrOk,              8, "R:R до TP2 ≥ 1.4",                            "цели TP2 слишком близко");

        addTradeLevels(s, p);
        if (z != null) addZoneLevels(s, z);
        addDeviationLevel(s, r);

        boolean core = p.entryConfirmed && htfAligned;
        return s.build(core && s.score >= 52);
    }

    private static StrategySignal continuation(AnalysisResult r) {
        TradePlan p = r.plan;
        Score s = new Score(r, "a-plus-continuation",
                "A+ SMC continuation",
                "Работа по направлению HTF: качественная OB/FVG модель, displacement и вход без контртренда.");
        if (!hasPlan(s, p)) return s.build(false);
        direction(s, p);
        require(s, p.entryConfirmed, 18, "вход подтвержден", "нет подтвержденного входа");
        require(s, p.longSide == r.htfBullish, 18, "HTF совпадает с направлением", "HTF против сделки");
        Zone z = p.zone;
        require(s, z != null && z.highQualityModel, 18, "A+ smart-money модель", "зона не A+");
        require(s, z != null && z.displacementScore >= 60, 10, "displacement 60+", "слабый displacement");
        require(s, r.imbalanceContext != null && r.imbalanceContext.supports(p.longSide), 10,
                "imbalance по направлению", "imbalance не подтверждает");
        require(s, !chop(r), 8, "рынок не chop", "chop/низкая эффективность");
        require(s, noEventBlock(r), 8, "event risk ok", "event/news риск");
        addTradeLevels(s, p);
        return s.build(s.score >= 68 && s.blockers.isEmpty());
    }

    private static StrategySignal sweepReversal(AnalysisResult r) {
        TradePlan p = r.plan;
        Score s = new Score(r, "liquidity-sweep-reversal",
                "Liquidity sweep reversal",
                "Вход после снятия ликвидности: sweep, возврат внутрь диапазона и подтверждение разворота.");
        if (!hasPlan(s, p)) return s.build(false);
        direction(s, p);
        boolean sweep = p.sweepIndex >= 0
                || (p.zone != null && p.zone.sweepConfirmed)
                || r.sweeps.stream().anyMatch(x -> x.reversalDirection == (p.longSide ? Direction.BULLISH : Direction.BEARISH)
                && x.score >= 60);
        require(s, sweep, 22, "есть sweep ликвидности", "нет явного sweep");
        require(s, p.entryConfirmed, 16, "вход подтвержден", "вход не подтвержден");
        require(s, p.chochIndex >= 0, 14, "есть CHoCH/BOS", "нет свежего CHoCH/BOS");
        require(s, r.deviationContext != null && r.deviationContext.supports(p.longSide), 12,
                "deviation поддерживает", "deviation не поддерживает");
        require(s, r.imbalanceContext != null && r.imbalanceContext.supports(p.longSide), 8,
                "imbalance за разворот", "imbalance слабый");
        require(s, noEventBlock(r), 8, "event risk ok", "event/news риск");
        addTradeLevels(s, p);
        addDeviationLevel(s, r);
        return s.build(s.score >= 64 && s.blockers.size() <= 1);
    }

    private static StrategySignal ifvgBreaker(AnalysisResult r) {
        TradePlan p = r.plan;
        Score s = new Score(r, "ifvg-breaker-reclaim",
                "IFVG / Breaker reclaim",
                "Продолжение после инверсии FVG или breaker block: рынок должен закрепиться после reclaim.");
        if (!hasPlan(s, p)) return s.build(false);
        direction(s, p);
        Zone z = p.zone;
        boolean model = z != null && (z.inverted || z.breakerBlock);
        require(s, model, 24, "IFVG/Breaker модель", "нет IFVG/Breaker");
        require(s, p.entryConfirmed, 16, "reclaim подтвержден", "reclaim не подтвержден");
        require(s, z != null && z.displacementScore >= 58, 12, "есть импульс после reclaim", "слабый импульс");
        require(s, r.imbalanceContext != null && r.imbalanceContext.supports(p.longSide), 10,
                "imbalance совпадает", "imbalance против/нейтрален");
        require(s, !eventShock(r), 8, "без новостного шока", "новостной/аномальный импульс");
        addTradeLevels(s, p);
        if (z != null) {
            s.level("reclaim zone", z.mid(), StrategySignal.LevelType.TRIGGER);
        }
        return s.build(s.score >= 62 && model);
    }

    private static StrategySignal liquidityRun(AnalysisResult r) {
        TradePlan p = r.plan;
        Score s = new Score(r, "liquidity-to-liquidity-run",
                "Liquidity-to-liquidity run",
                "Сделка от внутренней зоны к внешнему пулу ликвидности без жадного TP дальше 3R.");
        if (!hasPlan(s, p)) return s.build(false);
        direction(s, p);
        Zone z = p.zone;
        require(s, z != null && z.liquidityToLiquidity, 22, "L2L модель", "нет liquidity-to-liquidity модели");
        require(s, p.rr2() >= 1.35 && p.rr3() >= 2.0, 14, "R:R достаточно", "цели слишком близко");
        require(s, p.rr3() <= 3.35, 8, "TP3 не жадный", "TP3 слишком далеко");
        require(s, r.marketRegime != null && r.marketRegime.trendLike(), 10,
                "режим поддерживает движение", "режим не трендовый");
        require(s, r.imbalanceContext != null && r.imbalanceContext.supports(p.longSide), 10,
                "imbalance за движение", "imbalance не за движение");
        require(s, noEventBlock(r), 8, "event risk ok", "event/news риск");
        addTradeLevels(s, p);
        if (Double.isFinite(p.liquidityTarget)) {
            s.level("liquidity target", p.liquidityTarget, StrategySignal.LevelType.TARGET);
        }
        return s.build(s.score >= 66 && s.blockers.size() <= 1);
    }

    private static StrategySignal oteReversion(AnalysisResult r) {
        TradePlan p = r.plan;
        Score s = new Score(r, "ote-mean-reversion",
                "OTE mean reversion",
                "Возврат из discount/premium зоны к equilibrium после ловушки или слабого экстремума.");
        if (!hasPlan(s, p)) return s.build(false);
        direction(s, p);
        Zone z = p.zone;
        boolean pd = z != null && z.premiumDiscountAligned;
        boolean deviation = r.deviationContext != null && r.deviationContext.supports(p.longSide);
        boolean currentCorrectSide = Double.isFinite(r.equilibrium)
                && (p.longSide ? r.currentPrice <= r.equilibrium : r.currentPrice >= r.equilibrium);
        require(s, pd || deviation, 20, "premium/discount или deviation есть", "нет PD/deviation основания");
        require(s, currentCorrectSide, 12, "цена на правильной стороне equilibrium", "цена уже не в выгодной зоне");
        require(s, p.entryConfirmed, 14, "вход подтвержден", "вход не подтвержден");
        require(s, r.marketRegime == null || r.marketRegime.type != MarketRegime.Type.EXPANSION, 8,
                "нет агрессивного expansion против", "expansion может не дать откат");
        require(s, p.rr1() >= 0.8, 8, "до TP1 есть смысл", "мало места до TP1");
        addTradeLevels(s, p);
        if (Double.isFinite(r.equilibrium)) {
            s.level("equilibrium", r.equilibrium, StrategySignal.LevelType.TARGET);
        }
        return s.build(s.score >= 58 && (pd || deviation));
    }

    private static StrategySignal bosPullback(AnalysisResult r) {
        TradePlan p = r.plan;
        Score s = new Score(r, "bos-pullback-continuation",
                "BOS pullback continuation",
                "Continuation after a body-close BOS: impulse breaks structure, then the current zone gives a cleaner pullback entry with HTF support.");
        if (!hasPlan(s, p)) return s.build(false);
        direction(s, p);
        boolean bos = recentStructureSupports(r, p.longSide, "BOS", 58);
        require(s, bos, 20, "fresh BOS in trade direction", "no fresh BOS in trade direction");
        require(s, p.longSide == r.htfBullish, 14, "HTF agrees with BOS", "HTF is against the strategy");
        require(s, recentDisplacementSupports(r, p.longSide, 62), 14,
                "BOS has displacement behind it", "no strong displacement behind BOS");
        require(s, p.zone != null && (p.zone.highQualityModel || p.zone.structureScore >= 65), 12,
                "pullback zone has structure quality", "pullback zone is weak");
        require(s, !chop(r), 8, "market is not chop", "chop breaks continuation");
        require(s, noEventBlock(r), 8, "event risk ok", "event/news risk");
        addTradeLevels(s, p);
        addLatestStructureLevel(s, r, p.longSide, "BOS");
        return s.build(s.score >= 66 && s.blockers.size() <= 1);
    }

    private static StrategySignal propulsionBlock(AnalysisResult r) {
        TradePlan p = r.plan;
        Score s = new Score(r, "propulsion-block-drive",
                "Propulsion block drive",
                "Drive continuation from a propulsion block: the zone should be created by impulse and supported by trend-like regime plus imbalance.");
        if (!hasPlan(s, p)) return s.build(false);
        direction(s, p);
        Zone z = p.zone;
        boolean model = z != null && z.propulsionBlock;
        require(s, model, 22, "propulsion block detected", "no propulsion block");
        require(s, z != null && z.displacementScore >= 66, 16,
                "zone was created by strong impulse", "zone impulse is weak");
        require(s, r.marketRegime != null && r.marketRegime.trendLike(), 12,
                "regime supports drive", "regime is not trend-like");
        require(s, r.imbalanceContext != null && r.imbalanceContext.supports(p.longSide), 10,
                "imbalance supports drive", "imbalance does not support drive");
        require(s, p.entryConfirmed, 10, "entry is confirmed", "entry is not confirmed yet");
        require(s, noEventBlock(r), 8, "event risk ok", "event/news risk");
        addTradeLevels(s, p);
        addZoneLevels(s, z);
        return s.build(s.score >= 64 && model && s.blockers.size() <= 1);
    }

    private static StrategySignal mitigationRetest(AnalysisResult r) {
        TradePlan p = r.plan;
        Score s = new Score(r, "mitigation-retest",
                "Mitigation block retest",
                "Retest of a mitigation block after price returns into the POI: needs PD alignment and sweep/CHoCH evidence before execution.");
        if (!hasPlan(s, p)) return s.build(false);
        direction(s, p);
        Zone z = p.zone;
        boolean model = z != null && (z.mitigationBlock || contains(z.mechanic, "mitigation")
                || contains(p.setupType, "mitigation"));
        require(s, model, 22, "mitigation model detected", "no mitigation block");
        require(s, z != null && z.premiumDiscountAligned, 12,
                "premium/discount aligned", "PD does not confirm price");
        require(s, z != null && (z.sweepConfirmed || p.sweepIndex >= 0 || p.chochIndex >= 0), 14,
                "sweep/CHoCH before retest", "no sweep/CHoCH before retest");
        require(s, p.entryConfirmed, 12, "retest is confirmed", "retest is not confirmed yet");
        require(s, p.rr2() >= 1.35, 8, "R:R to TP2 is acceptable", "TP2 is too close");
        require(s, !eventShock(r), 8, "no shock impulse", "news/anomaly impulse");
        addTradeLevels(s, p);
        addZoneLevels(s, z);
        return s.build(s.score >= 62 && model);
    }

    private static StrategySignal orderFlowDelta(AnalysisResult r) {
        TradePlan p = r.plan;
        Score s = new Score(r, "order-flow-delta-confirmation",
                "Order-flow delta confirmation",
                "Trade only when footprint/delta confirms the entry direction: aggressor imbalance and latest delta should not fight the plan.");
        if (!hasPlan(s, p)) return s.build(false);
        direction(s, p);
        boolean available = r.orderFlowContext != null && !r.orderFlowContext.candles.isEmpty();
        require(s, available, 14, "order flow is available", "no order-flow data");
        require(s, available && r.orderFlowContext.supportsDirection(p.longSide), 18,
                "latest delta supports direction", "latest delta is against direction");
        require(s, available && r.orderFlowContext.score >= 55, 12,
                "order-flow score 55+", "order-flow score is weak");
        require(s, available && (p.longSide
                        ? r.orderFlowContext.buyImbalanceCount >= r.orderFlowContext.sellImbalanceCount
                        : r.orderFlowContext.sellImbalanceCount >= r.orderFlowContext.buyImbalanceCount), 10,
                "aggressor imbalance agrees", "aggressor imbalance disagrees");
        require(s, p.entryConfirmed, 10, "entry is confirmed", "entry is not confirmed yet");
        require(s, noEventBlock(r), 8, "event risk ok", "event/news risk");
        addTradeLevels(s, p);
        return s.build(s.score >= 62 && available && s.blockers.size() <= 1);
    }

    private static StrategySignal derivativesSqueeze(AnalysisResult r) {
        TradePlan p = r.plan;
        Score s = new Score(r, "derivatives-squeeze",
                "Derivatives squeeze",
                "Contrarian crowd setup: long against crowded shorts or short against crowded longs, preferably after sweep/deviation.");
        if (!hasPlan(s, p)) return s.build(false);
        direction(s, p);
        boolean available = r.derivativesContext != null && r.derivativesContext.available;
        boolean crowd = available && (p.longSide ? r.derivativesContext.crowdShorts() : r.derivativesContext.crowdLongs());
        require(s, available, 12, "derivatives data is available", "no derivatives data");
        require(s, crowd, 20, "funding shows crowded opposite side", "crowd is not stretched against trade");
        require(s, available && r.derivativesContext.openInterestExpanding(), 12,
                "open interest is expanding", "open interest is not expanding");
        require(s, hasSweepForPlan(r, p) || (r.deviationContext != null && r.deviationContext.supports(p.longSide)), 14,
                "sweep/deviation before squeeze", "no sweep/deviation before squeeze");
        require(s, p.entryConfirmed, 10, "entry is confirmed", "entry is not confirmed yet");
        require(s, !eventShock(r), 8, "no shock impulse", "news/anomaly impulse");
        addTradeLevels(s, p);
        addDeviationLevel(s, r);
        return s.build(s.score >= 64 && crowd && s.blockers.size() <= 1);
    }

    private static StrategySignal orderBookWallBounce(AnalysisResult r) {
        TradePlan p = r.plan;
        Score s = new Score(r, "orderbook-wall-bounce",
                "Order-book wall bounce",
                "Entry from order-book density: bid wall supports long or ask wall pressures short, and the wall should be close to entry/stop geometry.");
        if (!hasPlan(s, p)) return s.build(false);
        direction(s, p);
        boolean available = r.orderBookContext != null && r.orderBookContext.available;
        double wall = orderBookWallPrice(r, p.longSide);
        boolean sideSupports = available && r.orderBookContext.adjustment(p.longSide) >= 5;
        boolean wallNear = wallNearPlan(wall, p);
        require(s, available, 12, "order book is available", "no order book");
        require(s, sideSupports, 18, "book supports direction", "book is neutral or against direction");
        require(s, wallNear, 12, "large wall is near the plan", "wall is far from entry/stop");
        require(s, p.entryConfirmed, 10, "entry is confirmed", "entry is not confirmed yet");
        require(s, noEventBlock(r), 8, "event risk ok", "event/news risk");
        addTradeLevels(s, p);
        if (Double.isFinite(wall)) s.level("book wall", wall, StrategySignal.LevelType.TRIGGER);
        return s.build(s.score >= 58 && available && sideSupports);
    }

    private static StrategySignal killzoneSweep(AnalysisResult r) {
        TradePlan p = r.plan;
        Score s = new Score(r, "killzone-session-sweep",
                "Killzone session sweep",
                "Session model: price sweeps Asia/session liquidity during killzone, returns inside the range and confirms entry.");
        if (!hasPlan(s, p)) return s.build(false);
        direction(s, p);
        boolean session = r.sessionContext != null;
        boolean sessionSweep = hasSweepForPlan(r, p)
                || (r.deviationContext != null && r.deviationContext.available
                && (r.deviationContext.type == app.deviation.DeviationContext.Type.SESSION
                || r.deviationContext.type == app.deviation.DeviationContext.Type.RANGE_HIGH
                || r.deviationContext.type == app.deviation.DeviationContext.Type.RANGE_LOW)
                && r.deviationContext.supports(p.longSide));
        require(s, session && r.sessionContext.inKillzone, 16,
                "active killzone", "outside killzone");
        require(s, sessionSweep, 20, "session/range sweep detected", "no session/range sweep");
        require(s, r.deviationContext != null && r.deviationContext.returnedInside, 12,
                "price returned inside range", "no return inside range");
        require(s, p.entryConfirmed, 10, "entry is confirmed", "entry is not confirmed yet");
        require(s, !chop(r), 6, "market is not noisy chop", "chop lowers sweep quality");
        require(s, noEventBlock(r), 8, "event risk ok", "event/news risk");
        addTradeLevels(s, p);
        addSessionLevels(s, r);
        return s.build(s.score >= 62 && sessionSweep && s.blockers.size() <= 1);
    }

    private static StrategySignal equilibriumScalp(AnalysisResult r) {
        TradePlan p = r.plan;
        Score s = new Score(r, "range-equilibrium-scalp",
                "Range equilibrium scalp",
                "Small range scalp from discount/premium back to equilibrium without stretching targets into a trend-style objective.");
        if (!hasPlan(s, p)) return s.build(false);
        direction(s, p);
        boolean range = r.marketRegime != null && r.marketRegime.type == MarketRegime.Type.RANGE;
        boolean eqFinite = Double.isFinite(r.equilibrium) && r.equilibrium > 0.0;
        boolean correctSide = eqFinite && (p.longSide ? p.entry < r.equilibrium : p.entry > r.equilibrium);
        require(s, range, 18, "range regime", "market is not range");
        require(s, correctSide, 18, "entry is on correct side of equilibrium", "entry has no clean path to equilibrium");
        require(s, p.rr1() >= 0.75 && p.rr1() <= 1.45, 10,
                "TP1 fits scalp geometry", "TP1 does not look like a scalp target");
        require(s, r.deviationContext != null && (r.deviationContext.supports(p.longSide)
                || r.deviationContext.type == app.deviation.DeviationContext.Type.VWAP_MEAN), 10,
                "deviation/VWAP mean context", "no mean-reversion context");
        require(s, p.entryConfirmed, 8, "entry is confirmed", "entry is not confirmed yet");
        require(s, noEventBlock(r), 8, "event risk ok", "event/news risk");
        addTradeLevels(s, p);
        if (eqFinite) s.level("equilibrium", r.equilibrium, StrategySignal.LevelType.TARGET);
        return s.build(s.score >= 58 && range && correctSide);
    }

    private static boolean hasPlan(Score score, TradePlan plan) {
        if (plan != null) return true;
        score.blockers.add("на графике нет торгового плана");
        return false;
    }

    private static void direction(Score s, TradePlan p) {
        s.longSide = p == null || p.longSide;
        s.tone = s.longSide ? StrategySignal.Tone.LONG : StrategySignal.Tone.SHORT;
    }

    private static void require(Score s, boolean condition, int points, String ok, String fail) {
        if (condition) {
            s.score += points;
            s.confirmations.add(ok);
        } else {
            s.blockers.add(fail);
        }
    }

    private static void addTradeLevels(Score s, TradePlan p) {
        if (p == null) return;
        s.level("entry", p.entry, StrategySignal.LevelType.ENTRY);
        s.level("stop", p.stop, StrategySignal.LevelType.STOP);
        s.level("TP1", p.tp1, StrategySignal.LevelType.TARGET);
        s.level("TP2", p.tp2, StrategySignal.LevelType.TARGET);
        s.level("TP3", p.tp3, StrategySignal.LevelType.TARGET);
    }

    private static void addDeviationLevel(Score s, AnalysisResult r) {
        if (r.deviationContext == null || !Double.isFinite(r.deviationContext.deviationPrice)) return;
        s.level("deviation", r.deviationContext.deviationPrice, StrategySignal.LevelType.TRIGGER);
    }

    private static void addZoneLevels(Score s, Zone z) {
        if (z == null) return;
        s.level("zone top", z.top, StrategySignal.LevelType.ZONE);
        s.level("zone mid", z.mid(), StrategySignal.LevelType.ZONE);
        s.level("zone bot", z.bottom, StrategySignal.LevelType.ZONE);
    }

    private static void addSessionLevels(Score s, AnalysisResult r) {
        if (r.sessionContext == null) return;
        s.level("Asia high", r.sessionContext.asiaHigh, StrategySignal.LevelType.TRIGGER);
        s.level("Asia low", r.sessionContext.asiaLow, StrategySignal.LevelType.TRIGGER);
        s.level("session high", r.sessionContext.sessionHigh, StrategySignal.LevelType.TRIGGER);
        s.level("session low", r.sessionContext.sessionLow, StrategySignal.LevelType.TRIGGER);
    }

    private static void addLatestStructureLevel(Score s, AnalysisResult r, boolean longSide, String type) {
        Direction direction = directionFor(longSide);
        for (int i = r.structureEvents.size() - 1; i >= 0; i--) {
            StructureEvent event = r.structureEvents.get(i);
            if (event == null) continue;
            if (!type.equals(event.type)) continue;
            if (event.direction != direction) continue;
            s.level(type, event.brokenLevel, StrategySignal.LevelType.TRIGGER);
            return;
        }
    }

    private static boolean recentDisplacementSupports(AnalysisResult r, boolean longSide, int minScore) {
        Direction direction = directionFor(longSide);
        int from = Math.max(0, r.displacementEvents.size() - 4);
        for (int i = r.displacementEvents.size() - 1; i >= from; i--) {
            DisplacementEvent event = r.displacementEvents.get(i);
            if (event != null && event.direction == direction && event.score >= minScore) return true;
        }
        return false;
    }

    private static boolean recentStructureSupports(AnalysisResult r, boolean longSide, String type, int minScore) {
        Direction direction = directionFor(longSide);
        int from = Math.max(0, r.structureEvents.size() - 6);
        for (int i = r.structureEvents.size() - 1; i >= from; i--) {
            StructureEvent event = r.structureEvents.get(i);
            if (event == null) continue;
            if (!type.equals(event.type)) continue;
            if (event.direction == direction && event.score >= minScore && event.bodyCloseConfirmed) return true;
        }
        return false;
    }

    private static boolean hasSweepForPlan(AnalysisResult r, TradePlan p) {
        if (p == null) return false;
        if (p.sweepIndex >= 0 || (p.zone != null && p.zone.sweepConfirmed)) return true;
        Direction direction = directionFor(p.longSide);
        return r.sweeps.stream().anyMatch(x -> x != null && x.reversalDirection == direction && x.score >= 58);
    }

    private static double orderBookWallPrice(AnalysisResult r, boolean longSide) {
        if (r.orderBookContext == null || !r.orderBookContext.available) return Double.NaN;
        return longSide ? r.orderBookContext.largestBidWallPrice : r.orderBookContext.largestAskWallPrice;
    }

    private static boolean wallNearPlan(double wall, TradePlan p) {
        if (!Double.isFinite(wall) || p == null) return false;
        double risk = Math.max(p.risk(), Math.abs(p.entry) * 0.0005);
        boolean onCorrectSide = p.longSide ? wall <= p.entry : wall >= p.entry;
        return onCorrectSide && Math.abs(wall - p.entry) <= risk * 2.4;
    }

    private static Direction directionFor(boolean longSide) {
        return longSide ? Direction.BULLISH : Direction.BEARISH;
    }

    private static boolean contains(String value, String token) {
        return value != null && token != null
                && value.toLowerCase(java.util.Locale.ROOT).contains(token.toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean chop(AnalysisResult r) {
        return r.marketRegime != null && r.marketRegime.choppy();
    }

    private static boolean noEventBlock(AnalysisResult r) {
        return r.eventRiskContext == null || !r.eventRiskContext.blocksNewRisk();
    }

    private static boolean eventShock(AnalysisResult r) {
        return r.eventRiskContext != null && r.eventRiskContext.marketShock;
    }

    private static StrategySignal empty(String id, String name, String reason) {
        return new StrategySignal(id, name, "", "", "", false, true, 0,
                StrategySignal.Tone.WARNING, "Не подходит", "Нет активного графика",
                List.of(), List.of(reason), List.of());
    }

    private static class Score {
        final AnalysisResult result;
        final String id;
        final String name;
        final String description;
        final List<String> confirmations = new ArrayList<>();
        final List<String> blockers = new ArrayList<>();
        final List<StrategySignal.Level> levels = new ArrayList<>();
        boolean longSide = true;
        StrategySignal.Tone tone = StrategySignal.Tone.NEUTRAL;
        int score = 20;

        Score(AnalysisResult result, String id, String name, String description) {
            this.result = result;
            this.id = id;
            this.name = name;
            this.description = description;
        }

        void level(String label, double price, StrategySignal.LevelType type) {
            if (Double.isFinite(price) && price > 0.0) {
                levels.add(new StrategySignal.Level(label, price, type));
            }
        }

        StrategySignal build(boolean suitable) {
            int finalScore = Math.max(0, Math.min(100, score - blockers.size() * 4));
            StrategySignal.Tone finalTone = suitable ? tone : StrategySignal.Tone.WARNING;
            String side = longSide ? "LONG" : "SHORT";
            String verdict = suitable
                    ? "Подходит к текущему сетапу"
                    : "Не подходит к текущему сетапу";
            String action = suitable
                    ? side + ": можно смотреть по правилам стратегии"
                    : "Лучше не применять эту стратегию сейчас";
            return new StrategySignal(id, name, description,
                    result == null ? "" : result.symbol,
                    result == null ? "" : result.ltfInterval,
                    suitable, longSide, finalScore, finalTone, verdict, action,
                    confirmations, blockers, levels);
        }
    }
}
