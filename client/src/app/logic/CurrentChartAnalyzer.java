package app.logic;

import app.common.Direction;
import app.displacement.DisplacementEvent;
import app.liquidity.LiquiditySweep;
import app.model.AnalysisResult;
import app.model.Candle;
import app.model.ChartInsight;
import app.model.TradePlan;
import app.model.Zone;
import app.structure.MarketStructure;
import app.structure.StructureEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class CurrentChartAnalyzer {
    private static final double NEAR_ENTRY_PCT = 0.85;

    public ChartInsight analyze(AnalysisResult result,
                                String htfInterval,
                                String ltfInterval,
                                BinanceClient.Market market) {
        if (result == null) {
            return new ChartInsight("", htfInterval, ltfInterval,
                    marketLabel(market), "Нет данных", "Запусти обновление графика",
                    "Анализ текущего графика появится после загрузки свечей.",
                    0, ChartInsight.Tone.NEUTRAL,
                    List.of(), List.of(), List.of("Нет загруженного AnalysisResult"), List.of(), List.of());
        }

        String ltf = ltfInterval == null || ltfInterval.isBlank() ? result.ltfInterval : ltfInterval;
        TradePlan plan = result.plan;
        boolean scenarioLong = plan == null ? result.htfBullish : plan.longSide;
        Direction scenarioDirection = scenarioLong ? Direction.BULLISH : Direction.BEARISH;
        double current = finitePrice(result.currentPrice, result);

        Zone nearestZone = nearestZone(result, current, scenarioLong, true);
        if (nearestZone == null) nearestZone = nearestZone(result, current, scenarioLong, false);

        PricePosition position = pricePosition(result, current);
        boolean structureAligned = structureAligned(result, scenarioDirection);
        boolean nearEntry = plan != null && current > 0.0
                && Math.abs(current - plan.entry) / current * 100.0 <= NEAR_ENTRY_PCT;
        boolean entryChased = plan != null && current > 0.0 && hasMovedAwayFromEntry(plan, current);
        boolean eventBlocked = result.eventRiskContext != null && result.eventRiskContext.blocksNewRisk();

        int score = result.smcScore;
        if (plan != null) score += 5;
        if (plan != null && plan.entryConfirmed) score += 5;
        if (structureAligned) score += 6;
        if (nearEntry) score += 4;
        if (entryChased) score -= 8;
        if (nearestZone != null && nearestZone.bullish == scenarioLong && !nearestZone.mitigated) score += 4;
        if (result.marketRegime != null) {
            if (result.marketRegime.choppy()) score -= 10;
            else if (result.marketRegime.trendLike()) score += 4;
            if (result.marketRegime.compressed) score -= 4;
        }
        if (result.deviationContext != null && result.deviationContext.supports(scenarioLong)) {
            score += result.deviationContext.strongTrap() ? 7 : 3;
        }
        if (result.imbalanceContext != null && result.imbalanceContext.supports(scenarioLong)) {
            score += result.imbalanceContext.strong() ? 6 : 3;
        } else if (result.imbalanceContext != null && result.imbalanceContext.available
                && result.imbalanceContext.direction != Direction.NEUTRAL) {
            score -= 5;
        }
        if (eventBlocked) score -= 18;
        if (result.warnings != null) score -= Math.min(12, result.warnings.size() * 2);
        score = clamp(score, 0, 100);

        ChartInsight.Tone tone = tone(score, scenarioLong, plan, eventBlocked);
        String heading = heading(plan, result, scenarioLong, eventBlocked);
        String action = action(result, plan, score, nearEntry, entryChased, eventBlocked);
        String summary = summary(result, htfInterval, ltf, position, nearestZone);

        List<ChartInsight.Metric> metrics = metrics(result, htfInterval, ltf, market, current,
                position, nearestZone, plan, structureAligned, nearEntry, entryChased);
        List<String> confirmations = confirmations(result, scenarioDirection, plan, structureAligned);
        List<String> risks = risks(result, plan, nearestZone, nearEntry, entryChased);
        List<String> nextSteps = nextSteps(result, plan, nearEntry, entryChased, eventBlocked);
        List<ChartInsight.LevelMarker> levels = levels(result, plan, nearestZone, current);

        return new ChartInsight(result.symbol, htfInterval, ltf, marketLabel(market),
                heading, action, summary, score, tone,
                metrics, confirmations, risks, nextSteps, levels);
    }

    private List<ChartInsight.Metric> metrics(AnalysisResult result,
                                              String htfInterval,
                                              String ltfInterval,
                                              BinanceClient.Market market,
                                              double current,
                                              PricePosition position,
                                              Zone nearestZone,
                                              TradePlan plan,
                                              boolean structureAligned,
                                              boolean nearEntry,
                                              boolean entryChased) {
        List<ChartInsight.Metric> rows = new ArrayList<>();
        rows.add(metric("Синхронизация", safe(htfInterval) + " HTF -> " + safe(ltfInterval) + " график",
                ChartInsight.Tone.NEUTRAL));
        rows.add(metric("Рынок", marketLabel(market), ChartInsight.Tone.NEUTRAL));
        rows.add(metric("Цена", price(current), ChartInsight.Tone.NEUTRAL));
        rows.add(metric("Premium/discount", position.label, position.tone));
        rows.add(metric("MTF структура", structureAligned ? "HTF/LTF совпадают" : "есть расхождение",
                structureAligned ? ChartInsight.Tone.BULLISH : ChartInsight.Tone.WARNING));
        if (result.marketRegime != null) {
            ChartInsight.Tone tone = result.marketRegime.choppy()
                    ? ChartInsight.Tone.WARNING
                    : result.marketRegime.trendLike() ? ChartInsight.Tone.BULLISH : ChartInsight.Tone.NEUTRAL;
            rows.add(metric("Режим", regime(result.marketRegime.label()) + " · " + result.marketRegime.score + "/100", tone));
        }
        if (nearestZone != null) {
            double dist = current > 0.0 ? Math.abs(nearestZone.mid() - current) / current * 100.0 : 0.0;
            ChartInsight.Tone tone = nearestZone.mitigated ? ChartInsight.Tone.WARNING
                    : nearestZone.bullish ? ChartInsight.Tone.BULLISH : ChartInsight.Tone.BEARISH;
            rows.add(metric("Ближайшая зона", nearestZone.type + " · " + pct(dist) + " · " + zoneState(nearestZone), tone));
        }
        if (plan != null) {
            double distance = current > 0.0 ? Math.abs(current - plan.entry) / current * 100.0 : 0.0;
            rows.add(metric("Entry distance", pct(distance) + (nearEntry ? " · рядом" : entryChased ? " · не догонять" : ""),
                    nearEntry ? ChartInsight.Tone.BULLISH : entryChased ? ChartInsight.Tone.WARNING : ChartInsight.Tone.NEUTRAL));
            rows.add(metric("Risk:Reward", "1:" + two(plan.rr3()) + " · TP1/2/3 "
                    + two(plan.rr1()) + "/" + two(plan.rr2()) + "/" + two(plan.rr3()),
                    plan.rr3() >= 2.0 ? ChartInsight.Tone.BULLISH : ChartInsight.Tone.WARNING));
        }
        return rows;
    }

    private List<String> confirmations(AnalysisResult result,
                                       Direction scenarioDirection,
                                       TradePlan plan,
                                       boolean structureAligned) {
        List<String> list = new ArrayList<>();
        if (structureAligned) list.add("HTF и LTF структура синхронизированы по направлению сценария.");
        else list.add("HTF/LTF не полностью согласованы, вход требует дополнительного подтверждения.");

        LiquiditySweep sweep = latestSweep(result, scenarioDirection);
        if (sweep != null) {
            list.add("Свежий sweep: score " + sweep.score + "/100 · " + barsAgo(result, sweep.index));
        }
        DisplacementEvent displacement = latestDisplacement(result, scenarioDirection);
        if (displacement != null) {
            list.add("Displacement: " + displacement.quality + " · " + displacement.score + "/100 · " + barsAgo(result, displacement.index));
        }
        StructureEvent event = latestStructure(result, scenarioDirection);
        if (event != null) {
            list.add(event.type + " body close " + (event.bodyCloseConfirmed ? "подтвержден" : "слабый")
                    + " · score " + event.score + "/100.");
        }
        if (plan != null && plan.entryConfirmed) list.add("TradePlan подтвержден входной структурой.");
        if (result.sessionContext != null && result.sessionContext.inKillzone) {
            list.add("Активная killzone: " + result.sessionContext.activeSession + ".");
        }
        if (result.deviationContext != null && result.deviationContext.supports(plan == null ? result.htfBullish : plan.longSide)) {
            list.add("Deviation/smart-money trap поддерживает сценарий · " + result.deviationContext.score + "/100.");
        }
        if (result.imbalanceContext != null && result.imbalanceContext.supports(plan == null ? result.htfBullish : plan.longSide)) {
            list.add("Imbalance по направлению сделки · " + result.imbalanceContext.score + "/100.");
        }
        if (result.derivativesContext != null && result.derivativesContext.available) {
            list.add("OI/Funding: " + result.derivativesContext.message);
        }
        if (list.isEmpty()) list.add("Пока нет сильной цепочки подтверждений.");
        return limit(list, 8);
    }

    private List<String> risks(AnalysisResult result,
                               TradePlan plan,
                               Zone nearestZone,
                               boolean nearEntry,
                               boolean entryChased) {
        List<String> list = new ArrayList<>();
        if (result.eventRiskContext != null && result.eventRiskContext.available
                && result.eventRiskContext.score >= 55) {
            list.add("Event risk: " + result.eventRiskContext.message);
        }
        if (result.marketRegime != null && result.marketRegime.choppy()) {
            list.add("Chop/низкая эффективность: сигнал легче ломается шумом.");
        }
        if (result.marketRegime != null && result.marketRegime.compressed) {
            list.add("Волатильность сжата: возможен резкий ложный импульс.");
        }
        if (plan == null) {
            list.add("Торгового плана нет: зона без sweep/displacement/CHoCH не считается входом.");
        } else {
            if (!plan.entryConfirmed) list.add("Entry еще не подтвержден, это continuation/watchlist сценарий.");
            if (!nearEntry) list.add("Цена не в идеальной области entry.");
            if (entryChased) list.add("Цена ушла от entry, догонять рынок рискованно.");
            if (plan.rr3() < 1.35) list.add("R:R слабоват: один тейк не перекрывает серию ошибок.");
        }
        if (nearestZone != null && nearestZone.mitigated) {
            list.add("Ближайшая зона уже mitigated, повторная реакция слабее.");
        }
        if (result.imbalanceContext != null && result.imbalanceContext.available && plan != null
                && !result.imbalanceContext.supports(plan.longSide)) {
            list.add("Imbalance конфликтует с направлением TradePlan.");
        }
        if (result.orderBookContext != null && result.orderBookContext.available && plan != null
                && result.orderBookContext.adjustment(plan.longSide) < 0) {
            list.add("DOM/L2 сейчас против сделки: " + result.orderBookContext.message);
        }
        if (result.warnings != null) {
            for (String warning : result.warnings) list.add(warning);
        }
        return limit(list, 8);
    }

    private List<String> nextSteps(AnalysisResult result,
                                   TradePlan plan,
                                   boolean nearEntry,
                                   boolean entryChased,
                                   boolean eventBlocked) {
        List<String> list = new ArrayList<>();
        if (eventBlocked) {
            list.add("Новые сделки не открывать до затухания event risk.");
        }
        if (plan == null) {
            list.add("Ждать цепочку sweep -> displacement -> CHoCH/BOS -> возврат в OB/FVG.");
            list.add("Использовать график как watchlist, не как кнопку входа.");
        } else if (entryChased) {
            list.add("Не догонять цену: дождаться нового entry или переформирования зоны.");
            list.add("Если позиция уже открыта, вести риск от SL и частично фиксировать по силе импульса.");
        } else if (nearEntry) {
            list.add("Проверить DOM/order flow и риск перед исполнением.");
            list.add("SL/TP держать по TradePlan; TP3 не расширять выше полезного 1:3 без статистики.");
        } else {
            list.add("Дождаться возврата цены к entry, не открываться из середины движения.");
        }
        if (result.marketRegime != null && result.marketRegime.choppy()) {
            list.add("В chop снижать риск или пропускать сетап без сильного displacement.");
        }
        return limit(list, 5);
    }

    private List<ChartInsight.LevelMarker> levels(AnalysisResult result,
                                                  TradePlan plan,
                                                  Zone nearestZone,
                                                  double current) {
        List<ChartInsight.LevelMarker> levels = new ArrayList<>();
        addLevel(levels, "Цена", current, ChartInsight.LevelType.CURRENT, true);
        if (plan != null) {
            addLevel(levels, "Entry", plan.entry, ChartInsight.LevelType.ENTRY, false);
            addLevel(levels, "SL", plan.stop, ChartInsight.LevelType.STOP, false);
            addLevel(levels, "TP1", plan.tp1, ChartInsight.LevelType.TARGET, true);
            addLevel(levels, "TP2", plan.tp2, ChartInsight.LevelType.TARGET, true);
            addLevel(levels, "TP3", plan.tp3, ChartInsight.LevelType.TARGET, true);
        }
        addLevel(levels, "Equilibrium", result.equilibrium, ChartInsight.LevelType.EQUILIBRIUM, true);
        addLevel(levels, "Struct high", result.structHigh, ChartInsight.LevelType.STRUCTURE, true);
        addLevel(levels, "Struct low", result.structLow, ChartInsight.LevelType.STRUCTURE, true);
        if (nearestZone != null) {
            addLevel(levels, nearestZone.type + " top", nearestZone.top, ChartInsight.LevelType.ZONE, true);
            addLevel(levels, nearestZone.type + " mid", nearestZone.mid(), ChartInsight.LevelType.ZONE, true);
            addLevel(levels, nearestZone.type + " bot", nearestZone.bottom, ChartInsight.LevelType.ZONE, true);
        }
        if (result.deviationContext != null && result.deviationContext.available) {
            addLevel(levels, "Deviation", result.deviationContext.deviationPrice, ChartInsight.LevelType.ALERT, true);
        }
        return levels;
    }

    private void addLevel(List<ChartInsight.LevelMarker> levels,
                          String label,
                          double price,
                          ChartInsight.LevelType type,
                          boolean dashed) {
        if (Double.isFinite(price) && price > 0.0) {
            levels.add(new ChartInsight.LevelMarker(label, price, type, dashed));
        }
    }

    private Zone nearestZone(AnalysisResult result, double current, boolean longSide, boolean preferActiveSameSide) {
        if (result == null || result.zones == null || result.zones.isEmpty()
                || !Double.isFinite(current) || current <= 0.0) {
            return null;
        }
        return result.zones.stream()
                .filter(z -> z != null)
                .filter(z -> !preferActiveSameSide || (!z.mitigated && z.bullish == longSide))
                .min(Comparator.comparingDouble(z -> Math.abs(z.mid() - current)))
                .orElse(null);
    }

    private PricePosition pricePosition(AnalysisResult result, double current) {
        double range = result.structHigh - result.structLow;
        if (!Double.isFinite(current) || !Double.isFinite(range) || range <= 0.0) {
            return new PricePosition("нет диапазона", ChartInsight.Tone.NEUTRAL);
        }
        double ratio = (current - result.structLow) / range;
        if (ratio <= 0.35) return new PricePosition("discount · " + pct(ratio * 100.0), ChartInsight.Tone.BULLISH);
        if (ratio >= 0.65) return new PricePosition("premium · " + pct(ratio * 100.0), ChartInsight.Tone.BEARISH);
        return new PricePosition("equilibrium · " + pct(ratio * 100.0), ChartInsight.Tone.NEUTRAL);
    }

    private boolean structureAligned(AnalysisResult result, Direction scenarioDirection) {
        if (result == null || scenarioDirection == Direction.NEUTRAL) return false;
        MarketStructure htf = result.htfStructure;
        MarketStructure ltf = result.ltfStructure;
        boolean htfOk = htf == null || htf.bias == Direction.NEUTRAL || htf.bias == scenarioDirection;
        boolean ltfOk = ltf == null || ltf.bias == Direction.NEUTRAL || ltf.bias == scenarioDirection;
        return htfOk && ltfOk;
    }

    private LiquiditySweep latestSweep(AnalysisResult result, Direction direction) {
        if (result == null || result.sweeps == null || result.sweeps.isEmpty()) return null;
        for (int i = result.sweeps.size() - 1; i >= 0; i--) {
            LiquiditySweep sweep = result.sweeps.get(i);
            if (sweep != null && (direction == Direction.NEUTRAL || sweep.reversalDirection == direction)) return sweep;
        }
        return null;
    }

    private DisplacementEvent latestDisplacement(AnalysisResult result, Direction direction) {
        if (result == null || result.displacementEvents == null || result.displacementEvents.isEmpty()) return null;
        for (int i = result.displacementEvents.size() - 1; i >= 0; i--) {
            DisplacementEvent event = result.displacementEvents.get(i);
            if (event != null && (direction == Direction.NEUTRAL || event.direction == direction)) return event;
        }
        return null;
    }

    private StructureEvent latestStructure(AnalysisResult result, Direction direction) {
        if (result == null || result.structureEvents == null || result.structureEvents.isEmpty()) return null;
        for (int i = result.structureEvents.size() - 1; i >= 0; i--) {
            StructureEvent event = result.structureEvents.get(i);
            if (event != null && (direction == Direction.NEUTRAL || event.direction == direction)) return event;
        }
        return null;
    }

    private boolean hasMovedAwayFromEntry(TradePlan plan, double current) {
        double risk = plan.risk();
        if (risk <= 0.0) return false;
        return plan.longSide
                ? current > plan.entry + risk * 0.42
                : current < plan.entry - risk * 0.42;
    }

    private String heading(TradePlan plan, AnalysisResult result, boolean longSide, boolean eventBlocked) {
        if (eventBlocked) return "Риск-событие";
        if (plan != null) return (longSide ? "LONG" : "SHORT") + " сценарий · " + result.smcScore + "/100";
        return "Анализ текущего графика";
    }

    private String action(AnalysisResult result,
                          TradePlan plan,
                          int score,
                          boolean nearEntry,
                          boolean entryChased,
                          boolean eventBlocked) {
        if (eventBlocked) return "Пауза: event risk блокирует агрессивные входы";
        if (plan == null) return score >= 60 ? "Watchlist: есть контекст, но вход не готов" : "Ждать полный SMC сценарий";
        String side = plan.longSide ? "LONG" : "SHORT";
        if (entryChased) return side + ": не догонять, ждать новый entry";
        if (nearEntry && score >= 72) return side + ": можно готовить риск-чек";
        if (nearEntry) return side + ": ждать финальное подтверждение";
        return side + ": ждать цену у entry";
    }

    private String summary(AnalysisResult result,
                           String htfInterval,
                           String ltfInterval,
                           PricePosition position,
                           Zone nearestZone) {
        String zone = nearestZone == null
                ? "ближайшая зона не выделена"
                : nearestZone.type + " " + zoneState(nearestZone);
        String regime = result.marketRegime == null ? "режим неизвестен" : regime(result.marketRegime.label());
        return "HTF " + safe(htfInterval) + " синхронизирован с графиком " + safe(ltfInterval)
                + ". Цена: " + position.label + ", " + zone + ", режим " + regime + ".";
    }

    private ChartInsight.Tone tone(int score, boolean longSide, TradePlan plan, boolean eventBlocked) {
        if (eventBlocked || score < 35) return ChartInsight.Tone.DANGER;
        if (score < 55) return ChartInsight.Tone.WARNING;
        if (plan == null) return ChartInsight.Tone.NEUTRAL;
        return longSide ? ChartInsight.Tone.BULLISH : ChartInsight.Tone.BEARISH;
    }

    private double finitePrice(double current, AnalysisResult result) {
        if (Double.isFinite(current) && current > 0.0) return current;
        if (result != null && result.candles != null && !result.candles.isEmpty()) {
            Candle last = result.candles.get(result.candles.size() - 1);
            return last.close;
        }
        return Double.NaN;
    }

    private String barsAgo(AnalysisResult result, int index) {
        if (result == null || result.candles == null || result.candles.isEmpty() || index < 0) return "бар неизвестен";
        int bars = Math.max(0, result.candles.size() - 1 - index);
        return bars == 0 ? "текущий бар" : bars + " бар. назад";
    }

    private ChartInsight.Metric metric(String label, String value, ChartInsight.Tone tone) {
        return new ChartInsight.Metric(label, value, tone);
    }

    private List<String> limit(List<String> source, int max) {
        if (source.size() <= max) return source;
        return new ArrayList<>(source.subList(0, max));
    }

    private String zoneState(Zone zone) {
        if (zone == null) return "";
        if (zone.mitigated) return "mitigated";
        if (zone.entryConfirmed) return "entry confirmed";
        if (zone.sweepConfirmed) return "sweep confirmed";
        return zone.quality == null || zone.quality.isBlank() ? "active" : zone.quality;
    }

    private String marketLabel(BinanceClient.Market market) {
        return market == null ? "Futures" : market.label;
    }

    private String regime(String regime) {
        if ("trend".equals(regime)) return "тренд";
        if ("range".equals(regime)) return "диапазон";
        if ("expansion".equals(regime)) return "расширение";
        if ("chop".equals(regime)) return "chop";
        return regime == null || regime.isBlank() ? "--" : regime;
    }

    private String price(double value) {
        if (!Double.isFinite(value) || value <= 0.0) return "?";
        return String.format(Locale.US, "%.4f", value);
    }

    private String pct(double value) {
        if (!Double.isFinite(value)) return "?";
        return String.format(Locale.US, "%.2f%%", value);
    }

    private String two(double value) {
        if (!Double.isFinite(value)) return "?";
        return String.format(Locale.US, "%.2f", value);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "--" : value;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class PricePosition {
        final String label;
        final ChartInsight.Tone tone;

        PricePosition(String label, ChartInsight.Tone tone) {
            this.label = label;
            this.tone = tone;
        }
    }
}
