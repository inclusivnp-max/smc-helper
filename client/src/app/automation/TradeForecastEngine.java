package app.automation;

import app.learning.AdaptiveFactorWeights;
import app.backtest.BacktestIntelligence;
import app.logic.Backtester;
import app.logic.BinanceClient;
import app.model.AnalysisResult;
import app.model.Candle;
import app.model.TradePlan;

import java.util.ArrayList;
import java.util.List;

public class TradeForecastEngine {
    private static final double DEFAULT_TP1_SIZE = 0.40;
    private static final double DEFAULT_TP2_SIZE = 0.40;
    private static final double DEFAULT_TP3_SIZE = 0.20;

    public Decision evaluate(AnalysisResult result,
                             AdaptiveFactorWeights mlWeights,
                             Backtester.Report backtestReport) {
        return evaluate(result, mlWeights, backtestReport, ForecastProfile.BALANCED);
    }

    public Decision evaluate(AnalysisResult result,
                             AdaptiveFactorWeights mlWeights,
                             Backtester.Report backtestReport,
                             ForecastProfile profile) {
        ForecastProfile mode = profile == null ? ForecastProfile.BALANCED : profile;
        if (result == null || result.plan == null) {
            return new Decision(0, "NO TRADE", "Нет подтвержденного торгового плана",
                    List.of(), List.of("TradePlan отсутствует"));
        }

        TradePlan plan = result.plan;
        List<String> positives = new ArrayList<>();
        List<String> risks = new ArrayList<>();
        positives.add("Режим торгового плана: " + mode.label);
        double score = 42.0 + result.smcScore * 0.34 + plan.confidence * 0.22 + mode.scoreBias;
        int scoreCap = 100;

        if (plan.entryConfirmed) {
            score += 9;
            positives.add("Точка входа подтверждена алгоритмом");
        } else {
            score -= 28 * mode.penaltyMultiplier;
            scoreCap = Math.min(scoreCap, mode.capUnconfirmedEntry);
            risks.add("Вход еще не подтвержден");
        }

        RiskRewardQuality rrQuality = riskRewardQuality(plan);
        score += rrQuality.positive ? rrQuality.scoreAdjustment : rrQuality.scoreAdjustment * mode.penaltyMultiplier;
        scoreCap = Math.min(scoreCap, rrQuality.scoreCap);
        if (rrQuality.positive) positives.add(rrQuality.message);
        else risks.add(rrQuality.message);
        positives.add("Break-even winrate по TP 40/40/20: " + fmt(rrQuality.breakEvenWinrate) + "%");

        double distance = remainingToEntryPct(plan.longSide, result.currentPrice, plan.entry);
        double adversePastEntry = adversePastEntryPct(plan, result.currentPrice);
        if (stopAlreadyInvalidated(plan, result.currentPrice)) {
            score -= 55;
            scoreCap = Math.min(scoreCap, 28);
            risks.add("Цена уже за стопом сетапа, прогноз недействителен");
        } else if (adversePastEntry > 0.0) {
            score -= (8.0 + Math.min(16.0, adversePastEntry * 12.0)) * mode.penaltyMultiplier;
            scoreCap = Math.min(scoreCap, adversePastEntry >= 0.65 ? mode.capAdversePastEntrySevere : mode.capAdversePastEntry);
            risks.add("Цена уже прошла entry против направления: " + fmt(adversePastEntry) + "%");
        }

        if (distance <= 0.18) {
            score += 5;
            positives.add("Цена почти у входа: " + fmt(distance) + "%");
        } else if (distance <= 0.85) {
            score += 2;
            positives.add("Вход рядом: " + fmt(distance) + "%");
        } else {
            score -= 5;
            risks.add("До входа далеко: " + fmt(distance) + "%");
        }

        MarketRisk marketRisk = marketRisk(result, plan, mode);
        if (marketRisk.stale) {
            score -= 11 * mode.penaltyMultiplier;
            scoreCap = Math.min(scoreCap, mode.capStale);
            risks.add(marketRisk.staleReason);
        }
        if (marketRisk.adverseImpulse) {
            score -= (marketRisk.severeAdverseImpulse ? 24 : 14) * mode.penaltyMultiplier;
            scoreCap = Math.min(scoreCap, marketRisk.severeAdverseImpulse ? mode.capAdverseImpulseSevere : mode.capAdverseImpulse);
            risks.add(marketRisk.impulseReason);
        }
        if (marketRisk.lastCandleAgainst) {
            score -= 8 * mode.penaltyMultiplier;
            scoreCap = Math.min(scoreCap, mode.capLastCandleAgainst);
            risks.add(marketRisk.lastCandleReason);
        }

        if (result.marketRegime != null) {
            if (result.marketRegime.trendLike()) {
                score += 5;
                positives.add("Режим рынка поддерживает импульс: " + result.marketRegime.label());
            } else if (result.marketRegime.choppy()) {
                score -= 10;
                risks.add("Chop-режим повышает риск ложного входа");
            }
        }

        // NOTE on deviation/imbalance/event-risk below: SMCAnalyzer already folds all three
        // into result.smcScore (applyDeviationScore/applyImbalanceScore/applyEventRiskScore),
        // and smcScore already feeds this method's base `score` via `smcScore * 0.34` above.
        // Re-adding raw point deltas here double-counted the same evidence twice under two
        // different names. We still surface the narrative (trader wants to see *why*), and we
        // still keep the hard ceiling (scoreCap) — a cap expresses "don't let the total slip
        // past this regardless of the additive sum," which is a distinct, non-duplicative
        // safeguard — we only drop the duplicate additive score += / score -=.
        if (result.deviationContext != null && result.deviationContext.available) {
            if (result.deviationContext.supports(plan.longSide)) {
                positives.add("Smart-money deviation поддерживает сделку: "
                        + result.deviationContext.label + " · score " + result.deviationContext.score);
                if (result.deviationContext.premiumDiscount <= 0.35 && plan.longSide) {
                    positives.add("Deviation вернула цену в discount, лонг не выглядит поздним");
                }
                if (result.deviationContext.premiumDiscount >= 0.65 && !plan.longSide) {
                    positives.add("Deviation вернула цену в premium, шорт не выглядит поздним");
                }
            } else {
                scoreCap = Math.min(scoreCap, result.deviationContext.score >= 65 ? 72 : 86);
                risks.add("Deviation указывает против сделки: " + result.deviationContext.label);
            }
        } else {
            risks.add("Deviation/stop-hunt контекст не подтвержден");
        }

        if (result.imbalanceContext != null && result.imbalanceContext.available) {
            if (result.imbalanceContext.supports(plan.longSide)) {
                positives.add("Imbalance поддерживает сделку: " + result.imbalanceContext.label
                        + " · score " + result.imbalanceContext.score);
            } else if (result.imbalanceContext.score >= 50) {
                scoreCap = Math.min(scoreCap, result.imbalanceContext.score >= 70 ? 74 : 88);
                risks.add("Imbalance против сделки: " + result.imbalanceContext.label);
            }
        } else {
            risks.add("Imbalance контекст не подтвержден");
        }

        if (result.eventRiskContext != null && result.eventRiskContext.available) {
            if (result.eventRiskContext.blocksNewRisk()) {
                scoreCap = Math.min(scoreCap, 68);
                risks.add("Event risk высокий: " + result.eventRiskContext.message);
            } else if (result.eventRiskContext.score >= 55) {
                scoreCap = Math.min(scoreCap, 86);
                risks.add("Event risk повышен: " + result.eventRiskContext.message);
            } else {
                positives.add("Фундаментальный/event risk нормальный: " + result.eventRiskContext.score + "/100");
            }
        }

        boolean htfConflict = plan.longSide != result.htfBullish;
        if (htfConflict && !plan.entryConfirmed) {
            score -= 10 * mode.penaltyMultiplier;
            scoreCap = Math.min(scoreCap, mode.capHtfConflictUnconfirmed);
            risks.add("HTF bias конфликтует с направлением, а вход еще не подтвержден");
        } else if (htfConflict) {
            score -= 5 * mode.penaltyMultiplier;
            scoreCap = Math.min(scoreCap, mode.capHtfConflict);
            risks.add("Контртренд против HTF: размер позиции должен быть снижен");
        }

        if (result.orderFlowContext != null) {
            if (result.orderFlowContext.supportsDirection(plan.longSide)) {
                score += 5;
                positives.add("Order Flow / CVD поддерживает направление");
            } else {
                score -= 4;
                risks.add("Order Flow не подтверждает последний импульс");
            }
            if (result.orderFlowContext.tickBased) {
                score += 2;
                positives.add("Footprint построен на тиковой базе");
            }
        }

        // Derivatives/order-book adjustments below are already applied to result.smcScore by
        // SMCAnalyzer.applyExternalContext whenever these contexts exist — same reasoning as
        // deviation/imbalance/event-risk above. Narrative only, no second score delta.
        if (result.derivativesContext != null && result.derivativesContext.available) {
            int adjustment = result.derivativesContext.adjustment(plan.longSide, distance <= 0.85);
            if (adjustment > 0) positives.add("OI/Funding добавляет confluence: +" + adjustment);
            if (adjustment < 0) risks.add("OI/Funding предупреждает о риске: " + adjustment);
        }

        if (result.orderBookContext != null && result.orderBookContext.available) {
            int adjustment = result.orderBookContext.adjustment(plan.longSide);
            if (adjustment > 0) positives.add("DOM imbalance поддерживает вход: +" + adjustment);
            if (adjustment < 0) risks.add("DOM imbalance против входа: " + adjustment);
            if (result.orderBookContext.spoofingDetected) {
                positives.add("Есть spoofing-след: " + result.orderBookContext.spoofingSide);
            }
        }

        // ML factor weights are likewise already baked into result.smcScore upstream
        // (SMCAnalyzer calls weights.apply(...) unconditionally). Report their pull for
        // context, but don't add it to `score` a second time.
        AdaptiveFactorWeights weights = mlWeights == null ? result.adaptiveWeights : mlWeights;
        if (weights != null) {
            double avg = (weights.displacementWeight + weights.volumeWeight + weights.liquidityWeight
                    + weights.orderFlowWeight + weights.regimeWeight) / 5.0;
            double mlAdj = (avg - 1.0) * 18.0;
            if (mlAdj >= 1.0) positives.add("ML-веса усиливают сетап: +" + fmt(mlAdj));
            if (mlAdj <= -1.0) risks.add("ML-веса снижают качество: " + fmt(mlAdj));
        } else {
            risks.add("ML-веса не получены");
        }

        if (backtestReport != null) {
            if (backtestReport.executedTrades >= 10) {
                if (backtestReport.expectancy > 0.25) {
                    score += 9;
                    positives.add("Бэктест дает сильную expectancy: " + fmt(backtestReport.expectancy) + "R");
                } else if (backtestReport.expectancy > 0.0) {
                    score += 4;
                    positives.add("Бэктест положительный: " + fmt(backtestReport.expectancy) + "R");
                } else {
                    double penalty = (14.0 + Math.min(22.0, Math.abs(backtestReport.expectancy) * 13.0))
                            * mode.penaltyMultiplier;
                    score -= penalty;
                    scoreCap = Math.min(scoreCap, backtestReport.expectancy <= -0.50
                            ? mode.capNegativeBacktestSevere
                            : mode.capNegativeBacktest);
                    risks.add("Бэктест отрицательный: " + fmt(backtestReport.expectancy) + "R");
                }
                if (backtestReport.winrate < 35.0) {
                    score -= 9 * mode.penaltyMultiplier;
                    scoreCap = Math.min(scoreCap, mode.capLowWinrate);
                    risks.add("Winrate бэктеста ниже фильтра: " + fmt(backtestReport.winrate) + "%");
                }
                if (backtestReport.profitFactor >= 1.35) {
                    score += 3;
                } else if (backtestReport.profitFactor > 0.0 && backtestReport.profitFactor < 0.85) {
                    score -= 8 * mode.penaltyMultiplier;
                    scoreCap = Math.min(scoreCap, backtestReport.profitFactor < 0.50
                            ? mode.capWeakProfitFactorSevere
                            : mode.capWeakProfitFactor);
                    risks.add("Profit Factor слабый: " + fmt(backtestReport.profitFactor));
                }
                if (backtestReport.maxDrawdown >= 6.0) {
                    score -= (backtestReport.maxDrawdown >= 10.0 ? 10 : 5) * mode.penaltyMultiplier;
                    scoreCap = Math.min(scoreCap, backtestReport.maxDrawdown >= 10.0
                            ? mode.capHighDrawdownSevere
                            : mode.capHighDrawdown);
                    risks.add("Просадка в бэктесте высокая: " + fmt(backtestReport.maxDrawdown) + "R");
                }
                if (backtestReport.dataLakeTickCandles > 0) {
                    score += backtestReport.expectancy > 0.0 ? 3 : 0;
                    positives.add("Бэктест использовал Data Lake тики: " + backtestReport.dataLakeTickCandles);
                }
                if (backtestReport.websocketTickCandles > 0) {
                    score += backtestReport.expectancy > 0.0 ? 2 : 0;
                    positives.add("Backtest used WebSocket ticks: " + backtestReport.websocketTickCandles);
                }
                if (backtestReport.expectancy < 0.0 && backtestReport.profitFactor < 0.70 && backtestReport.winrate < 40.0) {
                    scoreCap = Math.min(scoreCap, mode.capStatisticalVeto);
                    risks.add("Статистический veto: отрицательная expectancy + слабый PF + низкий winrate");
                }
                if (backtestReport.expectancy <= 0.0 || backtestReport.profitFactor < 1.0) {
                    scoreCap = Math.min(scoreCap, mode.capNoPositiveEdge);
                    risks.add("A-score заблокирован: бэктест не доказал положительное матожидание");
                }
            } else {
                score -= 3 * mode.penaltyMultiplier;
                scoreCap = Math.min(scoreCap, mode.capFewTrades);
                risks.add("В бэктесте мало сделок для статистики: " + backtestReport.executedTrades);
            }
        } else {
            score -= 6 * mode.penaltyMultiplier;
            scoreCap = Math.min(scoreCap, mode.capNoBacktest);
            risks.add("Бэктест еще не выполнен");
        }

        BacktestIntelligence.Bucket historicalEdge = BacktestIntelligence.load()
                .context(result.symbol, result.ltfInterval, plan.longSide);
        if (historicalEdge != null && historicalEdge.trades >= 15) {
            if (historicalEdge.expectancy() > 0.30 && historicalEdge.profitFactor() >= 1.20) {
                score += 7;
                positives.add("Накопленная история CSV подтверждает edge: "
                        + historicalEdge.trades + " сдел., exp " + fmt(historicalEdge.expectancy()) + "R");
            } else if (historicalEdge.expectancy() > 0.05) {
                score += 3;
                positives.add("Накопленная история CSV слегка положительная: exp "
                        + fmt(historicalEdge.expectancy()) + "R");
            } else if (historicalEdge.expectancy() < -0.05) {
                double penalty = (7.0 + Math.min(14.0, Math.abs(historicalEdge.expectancy()) * 12.0))
                        * mode.penaltyMultiplier;
                score -= penalty;
                scoreCap = Math.min(scoreCap, historicalEdge.expectancy() <= -0.35
                        ? mode.capNegativeBacktestSevere
                        : mode.capNegativeBacktest);
                risks.add("Накопленная история CSV против сетапа: "
                        + historicalEdge.trades + " сдел., exp " + fmt(historicalEdge.expectancy()) + "R");
            }
            if (historicalEdge.winrate() < 35.0 && historicalEdge.expectancy() <= 0.0) {
                score -= 5 * mode.penaltyMultiplier;
                scoreCap = Math.min(scoreCap, mode.capLowWinrate);
                risks.add("Исторический winrate по этой связке слабый: " + fmt(historicalEdge.winrate()) + "%");
            }
        } else {
            risks.add("Накопленной CSV-статистики по этой монете/ТФ/направлению пока мало");
        }

        score -= Math.min(mode.warningPenaltyCap, result.warnings.size() * 2.3 * mode.penaltyMultiplier);
        for (String warning : result.warnings) {
            if (warning != null && !warning.isBlank() && risks.size() < 9) risks.add(warning);
        }

        int finalScore = Math.min(clamp((int) Math.round(score)), scoreCap);
        String verdict = verdict(finalScore);
        String action = action(finalScore, plan, distance);
        return new Decision(finalScore, verdict, action, positives, risks);
    }

    private String verdict(int score) {
        if (score >= 86) return "A+ EXECUTION READY";
        if (score >= 76) return "A CONDITIONAL EXECUTION";
        if (score >= 65) return "B WAIT FOR LIMIT / CONFIRM";
        return "NO TRADE / WAIT";
    }

    private String action(int score, TradePlan plan, double distance) {
        String side = plan.longSide ? "ЛОНГ" : "ШОРТ";
        if (score >= 86) return side + ": можно готовить лимитное исполнение через RiskGate";
        if (score >= 76) return side + ": вход только лимитом, риск уменьшить, ждать цену у entry";
        if (score >= 65) return side + ": держать в watchlist, исполнять после повторной проверки DOM/ML";
        return side + ": сделку не исполнять, ждать нового sweep/displacement";
    }

    private static RiskRewardQuality riskRewardQuality(TradePlan plan) {
        double rr1 = plan.rr1();
        double rr2 = plan.rr2();
        double rr3 = plan.rr3();
        double weightedReward = plan.weightedRewardR(DEFAULT_TP1_SIZE, DEFAULT_TP2_SIZE, DEFAULT_TP3_SIZE);
        double breakEvenWinrate = weightedReward <= 0.0 ? 100.0 : 100.0 / (1.0 + weightedReward);

        if (rr3 < 1.0 || weightedReward < 0.95) {
            return new RiskRewardQuality(-18.0, 42, false, breakEvenWinrate,
                    "R:R ниже минимума 1:1, матожидание слабое: TP3 1:" + fmt(rr3));
        }
        if (rr3 > 4.0) {
            return new RiskRewardQuality(-10.0, 76, false, breakEvenWinrate,
                    "R:R слишком дальний: 1:" + fmt(rr3) + ". Высокий TP часто снижает реализацию и ловит возврат в стоп");
        }
        if (rr3 > 3.35) {
            return new RiskRewardQuality(-4.0, 88, false, breakEvenWinrate,
                    "R:R жадный: 1:" + fmt(rr3) + ". Лучше подтверждать бэктестом или фиксировать часть у 1:2/1:3");
        }
        if (rr3 >= 2.65 && weightedReward >= 1.70) {
            return new RiskRewardQuality(7.0, 100, true, breakEvenWinrate,
                    "R:R хороший: TP1/TP2/TP3 = 1:" + fmt(rr1) + " / 1:" + fmt(rr2) + " / 1:" + fmt(rr3)
                            + ", одна успешная сделка перекрывает стоп");
        }
        if (rr3 >= 1.85 && weightedReward >= 1.35) {
            return new RiskRewardQuality(4.0, 100, true, breakEvenWinrate,
                    "R:R оптимальный средний: TP3 1:" + fmt(rr3) + ", weighted reward " + fmt(weightedReward) + "R");
        }
        if (rr3 >= 1.0) {
            return new RiskRewardQuality(-3.0, 92, false, breakEvenWinrate,
                    "R:R минимально допустимый, но запаса мало: TP3 1:" + fmt(rr3)
                            + ", нужен высокий winrate или лучший вход");
        }
        return new RiskRewardQuality(-12.0, 55, false, breakEvenWinrate,
                "R:R некорректный: TP3 1:" + fmt(rr3));
    }

    private static double remainingToEntryPct(boolean longSide, double currentPrice, double entry) {
        if (currentPrice <= 0.0 || entry <= 0.0) return 0.0;
        double remaining = longSide
                ? Math.max(0.0, currentPrice - entry)
                : Math.max(0.0, entry - currentPrice);
        return remaining / currentPrice * 100.0;
    }

    private static double adversePastEntryPct(TradePlan plan, double currentPrice) {
        if (plan == null || currentPrice <= 0.0 || plan.entry <= 0.0) return 0.0;
        double adverse = plan.longSide
                ? Math.max(0.0, plan.entry - currentPrice)
                : Math.max(0.0, currentPrice - plan.entry);
        return adverse / currentPrice * 100.0;
    }

    private static boolean stopAlreadyInvalidated(TradePlan plan, double currentPrice) {
        if (plan == null || currentPrice <= 0.0 || plan.stop <= 0.0) return false;
        return plan.longSide ? currentPrice <= plan.stop : currentPrice >= plan.stop;
    }

    private static MarketRisk marketRisk(AnalysisResult result, TradePlan plan, ForecastProfile mode) {
        MarketRisk risk = new MarketRisk();
        if (result == null || plan == null || result.candles == null || result.candles.size() < 8) return risk;

        List<Candle> candles = result.candles;
        Candle last = candles.get(candles.size() - 1);
        long ageMs = Math.max(0L, System.currentTimeMillis() - last.time);
        long intervalMs = Math.max(60_000L, BinanceClient.intervalMillis(result.ltfInterval));
        if (ageMs > Math.round(intervalMs * mode.staleCandleMultiplier)) {
            risk.stale = true;
            risk.staleReason = "Свечи устарели: прогноз нужно пересчитать перед входом";
        }

        double avgRangePct = avgRangePct(candles, 40);
        double fastAdverse = adverseMovePct(candles, plan.longSide, 3);
        double mediumAdverse = adverseMovePct(candles, plan.longSide, 8);
        double fastThreshold = Math.max(0.45, avgRangePct * 2.0) * mode.impulseThresholdMultiplier;
        double mediumThreshold = Math.max(0.85, avgRangePct * 3.2) * mode.impulseThresholdMultiplier;
        if (fastAdverse >= fastThreshold || mediumAdverse >= mediumThreshold) {
            risk.adverseImpulse = true;
            risk.severeAdverseImpulse = fastAdverse >= fastThreshold * 1.8 || mediumAdverse >= mediumThreshold * 1.7;
            risk.impulseReason = "Импульс против сделки: fast " + fmt(fastAdverse)
                    + "% / medium " + fmt(mediumAdverse) + "%";
        }

        double lastRangePct = last.close <= 0.0 ? 0.0 : last.range() / last.close * 100.0;
        boolean candleAgainst = plan.longSide ? last.bearish() : last.bullish();
        if (candleAgainst && last.bodyPercent() >= 0.62 && lastRangePct >= Math.max(0.35, avgRangePct * 1.8)) {
            risk.lastCandleAgainst = true;
            risk.lastCandleReason = "Последняя свеча displacement против направления сделки";
        }
        return risk;
    }

    public enum ForecastProfile {
        SOFT("Мягкий", 4.0, 0.82, 1.25, 4.0,
                70, 76, 64, 58, 74, 72, 58, 74, 68, 82,
                72, 62, 68, 60, 72, 66, 74, 74, 76, 74, 10),
        BALANCED("Средний", 0.0, 1.0, 1.0, 3.0,
                64, 70, 58, 52, 68, 66, 52, 70, 62, 76,
                68, 58, 62, 56, 66, 60, 72, 70, 72, 68, 14),
        PRECISE("Точный", -6.0, 1.22, 0.78, 2.0,
                56, 62, 48, 42, 58, 56, 44, 60, 54, 68,
                58, 50, 56, 48, 58, 52, 64, 62, 64, 58, 18);

        public final String label;
        final double scoreBias;
        final double penaltyMultiplier;
        final double impulseThresholdMultiplier;
        final double staleCandleMultiplier;
        final int capUnconfirmedEntry;
        final int capAdversePastEntry;
        final int capAdversePastEntrySevere;
        final int capAdverseImpulseSevere;
        final int capStale;
        final int capAdverseImpulse;
        final int capStatisticalVeto;
        final int capLastCandleAgainst;
        final int capHtfConflictUnconfirmed;
        final int capHtfConflict;
        final int capNegativeBacktest;
        final int capNegativeBacktestSevere;
        final int capLowWinrate;
        final int capWeakProfitFactorSevere;
        final int capWeakProfitFactor;
        final int capHighDrawdownSevere;
        final int capHighDrawdown;
        final int capFewTrades;
        final int capNoPositiveEdge;
        final int capNoBacktest;
        final double warningPenaltyCap;

        ForecastProfile(String label,
                        double scoreBias,
                        double penaltyMultiplier,
                        double impulseThresholdMultiplier,
                        double staleCandleMultiplier,
                        int capUnconfirmedEntry,
                        int capAdversePastEntry,
                        int capAdversePastEntrySevere,
                        int capAdverseImpulseSevere,
                        int capStale,
                        int capAdverseImpulse,
                        int capStatisticalVeto,
                        int capLastCandleAgainst,
                        int capHtfConflictUnconfirmed,
                        int capHtfConflict,
                        int capNegativeBacktest,
                        int capNegativeBacktestSevere,
                        int capLowWinrate,
                        int capWeakProfitFactorSevere,
                        int capWeakProfitFactor,
                        int capHighDrawdownSevere,
                        int capHighDrawdown,
                        int capFewTrades,
                        int capNoPositiveEdge,
                        int capNoBacktest,
                        double warningPenaltyCap) {
            this.label = label;
            this.scoreBias = scoreBias;
            this.penaltyMultiplier = penaltyMultiplier;
            this.impulseThresholdMultiplier = impulseThresholdMultiplier;
            this.staleCandleMultiplier = staleCandleMultiplier;
            this.capUnconfirmedEntry = capUnconfirmedEntry;
            this.capAdversePastEntry = capAdversePastEntry;
            this.capAdversePastEntrySevere = capAdversePastEntrySevere;
            this.capAdverseImpulseSevere = capAdverseImpulseSevere;
            this.capStale = capStale;
            this.capAdverseImpulse = capAdverseImpulse;
            this.capStatisticalVeto = capStatisticalVeto;
            this.capLastCandleAgainst = capLastCandleAgainst;
            this.capHtfConflictUnconfirmed = capHtfConflictUnconfirmed;
            this.capHtfConflict = capHtfConflict;
            this.capNegativeBacktest = capNegativeBacktest;
            this.capNegativeBacktestSevere = capNegativeBacktestSevere;
            this.capLowWinrate = capLowWinrate;
            this.capWeakProfitFactorSevere = capWeakProfitFactorSevere;
            this.capWeakProfitFactor = capWeakProfitFactor;
            this.capHighDrawdownSevere = capHighDrawdownSevere;
            this.capHighDrawdown = capHighDrawdown;
            this.capFewTrades = capFewTrades;
            this.capNoPositiveEdge = capNoPositiveEdge;
            this.capNoBacktest = capNoBacktest;
            this.warningPenaltyCap = warningPenaltyCap;
        }

        public static ForecastProfile fromLabel(Object value) {
            String text = value == null ? "" : value.toString().toLowerCase();
            if (text.contains("мяг")) return SOFT;
            if (text.contains("точ")) return PRECISE;
            return BALANCED;
        }

        @Override public String toString() {
            return label;
        }
    }

    private static double adverseMovePct(List<Candle> candles, boolean longSide, int lookback) {
        if (candles == null || candles.size() < lookback + 1) return 0.0;
        Candle from = candles.get(candles.size() - 1 - lookback);
        Candle last = candles.get(candles.size() - 1);
        if (from.close <= 0.0) return 0.0;
        double movePct = (last.close - from.close) / from.close * 100.0;
        return longSide ? Math.max(0.0, -movePct) : Math.max(0.0, movePct);
    }

    private static double avgRangePct(List<Candle> candles, int lookback) {
        if (candles == null || candles.isEmpty()) return 0.0;
        int from = Math.max(0, candles.size() - lookback);
        double sum = 0.0;
        int count = 0;
        for (int i = from; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            if (candle.close > 0.0) {
                sum += candle.range() / candle.close * 100.0;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }

    public static class Decision {
        public final int score;
        public final String verdict;
        public final String action;
        public final List<String> positives;
        public final List<String> risks;

        public Decision(int score, String verdict, String action,
                        List<String> positives, List<String> risks) {
            this.score = score;
            this.verdict = verdict == null ? "" : verdict;
            this.action = action == null ? "" : action;
            this.positives = positives == null ? List.of() : List.copyOf(positives);
            this.risks = risks == null ? List.of() : List.copyOf(risks);
        }
    }

    private static class MarketRisk {
        boolean stale;
        boolean adverseImpulse;
        boolean severeAdverseImpulse;
        boolean lastCandleAgainst;
        String staleReason = "";
        String impulseReason = "";
        String lastCandleReason = "";
    }

    private record RiskRewardQuality(double scoreAdjustment,
                                     int scoreCap,
                                     boolean positive,
                                     double breakEvenWinrate,
                                     String message) {
    }
}
