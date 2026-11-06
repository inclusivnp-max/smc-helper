package app.ai;

import app.Colors;
import app.backtest.RadarBacktestStats;
import app.common.Direction;
import app.common.I18n;
import app.derivatives.DerivativesContext;
import app.deviation.DeviationContext;
import app.displacement.DisplacementEvent;
import app.imbalance.ImbalanceContext;
import app.liquidity.LiquiditySweep;
import app.model.AnalysisResult;
import app.model.TradePlan;
import app.model.Zone;
import app.orderflow.OrderFlowContext;
import app.regime.MarketRegime;
import app.sessions.SessionContext;
import app.structure.MarketStructure;
import app.structure.StructureEvent;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;

/**
 * ИИ Прогноз — синтез всех алгоритмов программы в структурированный русскоязычный прогноз.
 * Никаких внешних API. Только внутренние алгоритмы.
 */
public class AiForecastEngine {

    public record Forecast(
            String symbol,
            String interval,
            String verdict,
            String quality,
            int confidence,
            String html
    ) {}

    public Forecast generate(AnalysisResult r) {
        if (r == null) return noData();
        if (I18n.english()) return generateEnglish(r);

        TradePlan plan = r.plan;
        String quality = plan != null ? plan.qualityRating() : "—";
        int confidence = plan != null ? plan.confidence : 0;

        String dirStr  = plan != null ? (plan.longSide ? "LONG"   : "SHORT")   : "—";
        String dirColor = plan != null ? (plan.longSide ? "#4caf78" : "#f07070") : "#999";
        String qColor = switch (quality) {
            case "A+" -> "#4caf78";
            case "A"  -> "#7ab8ff";
            case "B"  -> "#ffb347";
            default   -> "#f07070";
        };

        String verdict = computeVerdict(r, plan, quality);
        String vClass = verdictClass(verdict);

        StringBuilder h = new StringBuilder();
        h.append(cssHeader());

        h.append(heroHeader(r, plan, verdict, vClass, quality, qColor, confidence,
                dirStr, dirColor, false));

        // ── 1. Структура рынка ───────────────────────────────────────────────
        h.append(section("📐 Структура рынка"));

        boolean htfBull = r.htfBullish;
        h.append(row("HTF структура:", val(htfBull ? "good" : "bad", htfBull ? "Бычий" : "Медвежий")));

        if (r.htfStructure != null) {
            MarketStructure ms = r.htfStructure;
            h.append(row("HTF смещение:", val("", directionRu(ms.bias)) + " (сила: " + ms.strength + ")"));
            StructureEvent ev = ms.latestEvent();
            if (ev != null) h.append(row("Последнее HTF событие:", val("purple", ev.type)));
        }

        if (r.ltfStructure != null) {
            MarketStructure ms = r.ltfStructure;
            boolean aligned = (htfBull && ms.bias == Direction.BULLISH) || (!htfBull && ms.bias == Direction.BEARISH);
            h.append(row("LTF структура:",
                    val(aligned ? "good" : "warn", directionRu(ms.bias)) + "&nbsp;" +
                    tag(aligned ? "green" : "yellow", aligned ? "согласован" : "рассогласован")));
            StructureEvent ev = ms.latestEvent();
            if (ev != null) h.append(row("Последнее LTF событие:", val("purple", ev.type)));
        }

        if (r.marketRegime != null) {
            MarketRegime mr = r.marketRegime;
            h.append(row("Режим рынка:",
                    val(mr.choppy() ? "bad" : (mr.trendLike() ? "good" : "warn"), regimeRu(mr)) +
                    " (эффективность: " + pct(mr.efficiency) + ", ATR×" + fmt1(mr.atrExpansion) + ")" +
                    (mr.compressed ? "&nbsp;" + tag("yellow", "сжатие") : "")));
        }

        if (r.sessionContext != null) {
            SessionContext sc = r.sessionContext;
            h.append(row("Сессия:",
                    val("cyan", esc(sc.activeSession)) + " — " + esc(sc.phase) +
                    "&nbsp;" + tag(sc.inKillzone ? "green" : "blue", sc.inKillzone ? "Killzone ✓" : "вне Killzone")));
        }

        if (r.equilibrium > 0 && r.currentPrice > 0 && plan != null) {
            boolean aboveEq = r.currentPrice >= r.equilibrium;
            boolean favorable = (plan.longSide && !aboveEq) || (!plan.longSide && aboveEq);
            h.append(row("Premium/Discount:",
                    val(favorable ? "good" : "warn", aboveEq ? "Premium (выше 50%)" : "Discount (ниже 50%)")));
        }

        // ── 2. Свипы ликвидности ─────────────────────────────────────────────
        if (!r.sweeps.isEmpty()) {
            h.append(section("💧 Ликвидность"));
            LiquiditySweep best = r.sweeps.stream()
                    .max(Comparator.comparingInt(s -> s.score)).orElse(null);
            if (best != null) {
                boolean sweepAligned = plan != null &&
                        ((plan.longSide && best.reversalDirection == Direction.BULLISH) ||
                         (!plan.longSide && best.reversalDirection == Direction.BEARISH));
                String sweepDir = best.reversalDirection == Direction.BULLISH ? "Бычий разворот" : "Медвежий разворот";
                h.append(row("Лучший свип:", val(sweepAligned ? "good" : "warn", sweepDir) + " (score: " + best.score + ")"));
                h.append(row("Детали:",
                        tag(best.closeReclaimed ? "green" : "yellow", best.closeReclaimed ? "закрытие внутри" : "нет рекляйма") +
                        (best.displacementConfirmed ? "&nbsp;" + tag("green", "displacement ✓") : "") +
                        (best.session != null && !best.session.isEmpty() ? "&nbsp;" + tag("blue", esc(best.session)) : "")));
            }
            h.append(row("Всего свипов:", String.valueOf(r.sweeps.size())));
        }

        // ── 3. Зоны ─────────────────────────────────────────────────────────
        long activeZoneCount = r.zones.stream().filter(z -> !z.mitigated).count();
        if (activeZoneCount > 0) {
            h.append(section("🟦 Зоны"));
            Zone best = r.zones.stream().filter(z -> !z.mitigated)
                    .max(Comparator.comparingInt(z -> z.score)).orElse(null);
            if (best != null) {
                h.append(row("Лучшая зона:",
                        "<span class='val'>" + esc(zoneTypeRu(best.type)) + "</span>&nbsp;" +
                        val(best.bullish ? "good" : "bad", best.bullish ? "LONG" : "SHORT") +
                        " (score: <b>" + best.score + "</b>)" +
                        (best.institutional ? "&nbsp;" + tag("purple", "институциональная") : "") +
                        (best.highQualityModel ? "&nbsp;" + tag("purple", "HQ model") : "")));
                h.append(row("Диапазон:", priceRange(best.bottom, best.top, r.currentPrice)));
                if (!best.mechanic.isEmpty())
                    h.append(row("Механика:", val("cyan", esc(best.mechanic))));
                String zTags = tag(best.sweepConfirmed ? "green" : "yellow", best.sweepConfirmed ? "sweep ✓" : "sweep ждём") +
                        (best.entryConfirmed ? "&nbsp;" + tag("green", "entry ✓") : "") +
                        (best.breakerBlock ? "&nbsp;" + tag("red", "breaker") : "") +
                        (best.inverted ? "&nbsp;" + tag("yellow", "inverted") : "");
                h.append(row("Флаги:", zTags));
                h.append(row("Дисплейсмент зоны:", "<span class='val'>" + best.displacementScore + "</span>" +
                        " | структура: <span class='val'>" + best.structureScore + "</span>" +
                        " | объём: <span class='val'>" + best.volumeScore + "</span>"));
            }
            h.append(row("Активных зон:", activeZoneCount + " из " + r.zones.size()));
        }

        // ── 4. Дисплейсмент ─────────────────────────────────────────────────
        if (!r.displacementEvents.isEmpty()) {
            h.append(section("⚡ Дисплейсмент"));
            DisplacementEvent last = r.displacementEvents.get(r.displacementEvents.size() - 1);
            boolean dispAligned = plan == null ||
                    (plan.longSide && last.direction == Direction.BULLISH) ||
                    (!plan.longSide && last.direction == Direction.BEARISH);
            h.append(row("Последний импульс:",
                    val(dispAligned ? "good" : "warn", directionRu(last.direction)) +
                    " (score: " + last.score + ", ×ATR: " + fmt1(last.atrExpansion) + ")" +
                    (last.consecutiveImpulses > 1 ? "&nbsp;" + tag("green", last.consecutiveImpulses + "× подряд") : "")));
            h.append(row("Объём импульса:", "×" + fmt1(last.volumeRatio) + " от среднего"));
            h.append(row("Всего импульсов:", String.valueOf(r.displacementEvents.size())));
        }

        // ── 5. Торговый план ─────────────────────────────────────────────────
        if (plan != null) {
            h.append(section("📊 Торговый план"));
            h.append(row("Направление:", "<b style='color:" + dirColor + ";font-size:14px;'>" + dirStr + "</b>"));
            h.append(row("Вход:", val("", fmtPrice(plan.entry))));
            h.append(row("Стоп:", val("bad", fmtPrice(plan.stop))));
            h.append(row("TP1:", val("good", fmtPrice(plan.tp1)) + "&nbsp;&nbsp;R:R = " + fmt2(plan.rr1())));
            h.append(row("TP2 (приоритет):", val("good", fmtPrice(plan.tp2)) + "&nbsp;&nbsp;R:R = " + fmt2(plan.rr2())));
            h.append(row("TP3:", val("good", fmtPrice(plan.tp3)) + "&nbsp;&nbsp;R:R = " + fmt2(plan.rr3())));
            h.append(row("Риск (pts):", val("warn", fmtPrice(plan.risk()))));
            if (!plan.setupType.isEmpty() && !plan.setupType.equals("unclassified"))
                h.append(row("Тип сетапа:", val("cyan", esc(plan.setupType))));
            if (!plan.scenario.isEmpty() && !plan.scenario.equals("Legacy zone reaction"))
                h.append(row("Сценарий:", "<span class='val'>" + esc(plan.scenario) + "</span>"));
            if (!plan.liquidityTargetLabel.isEmpty())
                h.append(row("Цель ликвидности:",
                        val("purple", esc(plan.liquidityTargetLabel)) + " @ " + fmtPrice(plan.liquidityTarget)));
            h.append(row("Подтверждение:",
                    plan.entryConfirmed
                            ? tag("green", "Вход подтверждён ✓")
                            : tag("yellow", "Ожидаем подтверждение")));
        }

        // ── 6. Математика ────────────────────────────────────────────────────
        if (plan != null) {
            h.append(section("📈 Математика"));
            double ev = plan.expectedValueR(RadarBacktestStats.calibratedWinRate01(r, plan));
            String evSign = ev >= 0 ? "+" : "";
            h.append(row("Уверенность:", val("", plan.confidence + "%")));
            h.append(row("Рейтинг:", "<span class='val' style='color:" + qColor + ";font-size:14px;font-weight:bold;'>" + quality + "</span>"));
            h.append(row("E[V] (мат. ожидание):", val(ev > 0 ? "good" : (ev > -0.2 ? "warn" : "bad"), evSign + fmt2(ev) + "R")));
            h.append(row("Взвешенный R (40/40/20):", val("", fmt2(plan.weightedRewardR(0.4, 0.4, 0.2)) + "R")));
            h.append(row("SMC Score:", val("", r.smcScore + " / 100")));
        }

        // ── 7. Контексты ─────────────────────────────────────────────────────
        boolean hasCtx = hasContext(r);
        if (hasCtx) {
            h.append(section("🔍 Дополнительный контекст"));

            if (r.imbalanceContext != null && r.imbalanceContext.available) {
                ImbalanceContext ic = r.imbalanceContext;
                boolean aligned = plan == null || ic.supports(plan.longSide);
                h.append(row("Имбаланс:",
                        val(aligned ? "good" : "bad", directionRu(ic.direction)) +
                        " (score: " + ic.score + ")" +
                        (ic.absorption ? "&nbsp;" + tag("purple", "абсорбция") : "") +
                        (ic.strong() ? "&nbsp;" + tag("green", "сильный") : "")));
            }

            if (r.deviationContext != null && r.deviationContext.available) {
                DeviationContext dc = r.deviationContext;
                boolean aligned = plan == null || dc.supports(plan.longSide);
                h.append(row("Девиация:",
                        val(aligned ? "good" : "warn", esc(dc.label)) +
                        " (score: " + dc.score + ")" +
                        (dc.strongTrap() ? "&nbsp;" + tag("purple", "strong trap") : "") +
                        (dc.returnedInside ? "&nbsp;" + tag("green", "возврат ✓") : "")));
            }

            if (r.derivativesContext != null && r.derivativesContext.available) {
                DerivativesContext dc = r.derivativesContext;
                String funding = String.format("%+.4f%%", dc.fundingRate * 100);
                boolean fundingFav = plan != null &&
                        ((plan.longSide && dc.fundingRate < 0) || (!plan.longSide && dc.fundingRate > 0));
                h.append(row("Открытый интерес:", val("", esc(dc.state))));
                h.append(row("Фандинг ставка:", val(fundingFav ? "good" : "warn", funding)));
            }

            if (r.orderFlowContext != null) {
                OrderFlowContext of = r.orderFlowContext;
                boolean ofAligned = plan == null || of.supportsDirection(plan.longSide);
                String deltaSign = of.latestDelta >= 0 ? "+" : "";
                h.append(row("Order Flow delta:",
                        val(ofAligned ? "good" : "bad",
                                deltaSign + fmt1(of.latestDelta) +
                                " (" + String.format("%+.1f", of.latestDeltaPercent) + "%)") +
                        " (score: " + of.score + ")"));
            }
        }

        // ── 8. Подтверждающие факторы ────────────────────────────────────────
        List<String> allFactors = mergeFactors(r);
        if (!allFactors.isEmpty()) {
            h.append(section("✅ Факторы подтверждения"));
            for (String f : allFactors) {
                h.append("<div class='factor'>✔ ").append(esc(f)).append("</div>");
            }
        }

        // ── 9. Предупреждения ────────────────────────────────────────────────
        List<String> allWarns = mergeWarnings(r, plan);
        if (!allWarns.isEmpty()) {
            h.append(section("⚠️ Предупреждения"));
            for (String w : allWarns) {
                h.append("<div class='warn-item'>⚠ ").append(esc(w)).append("</div>");
            }
        }

        // ── 10. Нарратив ─────────────────────────────────────────────────────
        if (!r.narrative.isEmpty() && !r.narrative.equals("Legacy analysis")) {
            h.append(section("📝 Нарратив алгоритма"));
            h.append("<div class='summary'>").append(esc(r.narrative)).append("</div>");
        }

        // ── 11. ВЕРДИКТ ──────────────────────────────────────────────────────
        h.append(section("🎯 Вердикт"));
        String vIcon  = switch (verdict) { case "ВХОДИТЬ" -> "✅"; case "ЖДАТЬ" -> "⏳"; default -> "🚫"; };
        h.append("<div class='verdict ").append(vClass).append("'>")
                .append(vIcon).append("  ").append(verdict)
                .append(" &nbsp;—&nbsp; ").append(quality)
                .append(" &nbsp;—&nbsp; ").append(confidence).append("% уверенность")
                .append("</div>");
        h.append("<div class='summary'>").append(buildSummary(r, plan, verdict)).append("</div>");

        h.append("</body></html>");

        return new Forecast(r.symbol, r.ltfInterval, verdict, quality, confidence, h.toString());
    }

    // ── Вердикт ──────────────────────────────────────────────────────────────

    private Forecast generateEnglish(AnalysisResult r) {
        TradePlan plan = r.plan;
        String quality = plan != null ? plan.qualityRating() : "-";
        int confidence = plan != null ? plan.confidence : 0;
        String verdict = computeVerdict(r, plan, quality);
        String vClass = switch (verdict) {
            case "ВХОДИТЬ" -> "enter";
            case "ЖДАТЬ" -> "wait";
            default -> "skip";
        };
        String englishVerdict = switch (verdict) {
            case "ВХОДИТЬ" -> "ENTER";
            case "ЖДАТЬ" -> "WAIT";
            default -> "STAND ASIDE";
        };

        StringBuilder h = new StringBuilder();
        h.append(cssHeader());
        h.append(heroHeader(r, plan, englishVerdict, vClass, quality, qualityColor(quality), confidence,
                plan != null ? (plan.longSide ? "LONG" : "SHORT") : "-",
                plan != null ? (plan.longSide ? "#4caf78" : "#f07070") : "#8f96aa",
                true));

        h.append(section("Market Structure"));
        h.append(row("HTF bias:", val(r.htfBullish ? "good" : "bad", r.htfBullish ? "Bullish" : "Bearish")));
        if (r.ltfStructure != null) {
            h.append(row("LTF bias:", val("", directionEn(r.ltfStructure.bias))));
        }
        if (r.marketRegime != null) {
            h.append(row("Regime:", val(r.marketRegime.choppy() ? "bad" : "good", esc(r.marketRegime.label()))));
        }
        if (r.sessionContext != null) {
            h.append(row("Session:", val("cyan", esc(r.sessionContext.activeSession)) + " - " + esc(r.sessionContext.phase)));
        }

        if (plan != null) {
            h.append(section("Trade Plan"));
            h.append(row("Direction:", val(plan.longSide ? "good" : "bad", plan.longSide ? "LONG" : "SHORT")));
            h.append(row("Entry:", val("", fmtPrice(plan.entry))));
            h.append(row("Stop:", val("bad", fmtPrice(plan.stop))));
            h.append(row("TP1:", val("good", fmtPrice(plan.tp1)) + " &nbsp;R:R " + fmt2(plan.rr1())));
            h.append(row("TP2:", val("good", fmtPrice(plan.tp2)) + " &nbsp;R:R " + fmt2(plan.rr2())));
            h.append(row("TP3:", val("good", fmtPrice(plan.tp3)) + " &nbsp;R:R " + fmt2(plan.rr3())));
            h.append(row("Quality:", val("", quality + " / " + confidence + "%")));
            double ev = plan.expectedValueR(RadarBacktestStats.calibratedWinRate01(r, plan));
            h.append(row("Expected value:", val(ev > 0 ? "good" : "warn", fmt2(ev) + "R")));
            h.append(row("Entry status:", plan.entryConfirmed ? tag("green", "confirmed") : tag("yellow", "waiting")));
        }

        h.append(section("Context"));
        h.append(row("Liquidity sweeps:", String.valueOf(r.sweeps.size())));
        h.append(row("Active zones:", String.valueOf(r.zones.stream().filter(z -> !z.mitigated).count())));
        if (r.deviationContext != null && r.deviationContext.available) {
            h.append(row("Deviation:", val("purple", esc(r.deviationContext.label)) + " score " + r.deviationContext.score));
        }
        if (r.imbalanceContext != null && r.imbalanceContext.available) {
            h.append(row("Imbalance:", val(r.imbalanceContext.supports(plan != null && plan.longSide) ? "good" : "warn",
                    esc(r.imbalanceContext.label)) + " score " + r.imbalanceContext.score));
        }
        if (r.eventRiskContext != null && r.eventRiskContext.available) {
            h.append(row("Event risk:", val(r.eventRiskContext.blocksNewRisk() ? "bad" : "warn",
                    esc(r.eventRiskContext.message))));
        }

        List<String> factors = mergeFactors(r);
        if (!factors.isEmpty()) {
            h.append(section("Supporting Factors"));
            for (String factor : factors) h.append("<div class='factor'>✔ ").append(esc(factor)).append("</div>");
        }
        List<String> warnings = mergeWarnings(r, plan);
        if (!warnings.isEmpty()) {
            h.append(section("Warnings"));
            for (String warning : warnings) h.append("<div class='warn-item'>⚠ ").append(esc(warning)).append("</div>");
        }

        h.append(section("Verdict"));
        h.append("<div class='verdict ").append(vClass).append("'>")
                .append(englishVerdict).append(" &nbsp;-&nbsp; ")
                .append(quality).append(" &nbsp;-&nbsp; ")
                .append(confidence).append("% confidence</div>");
        h.append("<div class='summary'>").append(buildEnglishSummary(r, plan, englishVerdict)).append("</div>");
        h.append("</body></html>");
        return new Forecast(r.symbol, r.ltfInterval, englishVerdict, quality, confidence, h.toString());
    }

    private String buildEnglishSummary(AnalysisResult r, TradePlan plan, String verdict) {
        if (plan == null) return "No trade plan is available yet. Run analysis first.";
        String side = plan.longSide ? "long" : "short";
        StringBuilder sb = new StringBuilder();
        sb.append(esc(r.symbol)).append(" has a ").append(side)
                .append(" setup rated ").append(plan.qualityRating())
                .append(" with ").append(plan.confidence).append("% confidence. ");
        sb.append(plan.entryConfirmed
                ? "Entry is confirmed by the current model. "
                : "Entry is not confirmed yet; wait for mitigation/confirmation. ");
        if (r.eventRiskContext != null && r.eventRiskContext.blocksNewRisk()) {
            sb.append("Event risk blocks new aggressive exposure. ");
        }
        sb.append("Verdict: ").append(verdict).append(".");
        return sb.toString();
    }

    private String computeVerdict(AnalysisResult r, TradePlan plan, String quality) {
        if (plan == null) return "ВОЗДЕРЖАТЬСЯ";
        if (r.marketRegime != null && r.marketRegime.choppy()) return "ВОЗДЕРЖАТЬСЯ";
        if (r.eventRiskContext != null && r.eventRiskContext.blocksNewRisk()) return "ВОЗДЕРЖАТЬСЯ";
        if ("C".equals(quality)) return "ВОЗДЕРЖАТЬСЯ";
        if (plan.entryConfirmed && ("A+".equals(quality) || "A".equals(quality) || "B".equals(quality))) return "ВХОДИТЬ";
        return "ЖДАТЬ";
    }

    private String buildSummary(AnalysisResult r, TradePlan plan, String verdict) {
        if (plan == null) return "Торговый план не сформирован. Сначала нажмите «Анализ».";

        StringBuilder sb = new StringBuilder();
        String side = plan.longSide ? "лонговый" : "шортовый";

        sb.append(esc(r.symbol)).append(" показывает ").append(side)
                .append(" сетап класса ").append(plan.qualityRating())
                .append(" с уверенностью ").append(plan.confidence).append("%. ");

        boolean ltfAligned = r.ltfStructure != null &&
                ((plan.longSide && r.ltfStructure.bias == Direction.BULLISH) ||
                 (!plan.longSide && r.ltfStructure.bias == Direction.BEARISH));
        sb.append(ltfAligned
                ? "HTF и LTF структура согласованы — направление подтверждено. "
                : "HTF подтверждён, LTF требует дополнительного подтверждения. ");

        sb.append(plan.entryConfirmed
                ? "Вход подтверждён — свип ликвидности и CHoCH зафиксированы. "
                : "Вход ещё не подтверждён — ожидаем митигацию зоны. ");

        double ev = plan.expectedValueR(RadarBacktestStats.calibratedWinRate01(r, plan));
        if (ev > 0) {
            sb.append("Мат. ожидание положительное (E[V] = +").append(fmt2(ev)).append("R) — сделка статистически оправдана.");
        } else {
            sb.append("Мат. ожидание отрицательное — рассмотрите пропуск или сокращение размера позиции.");
        }
        return sb.toString();
    }

    // ── Утилиты ──────────────────────────────────────────────────────────────

    private boolean hasContext(AnalysisResult r) {
        return (r.imbalanceContext != null && r.imbalanceContext.available)
                || (r.deviationContext != null && r.deviationContext.available)
                || (r.derivativesContext != null && r.derivativesContext.available)
                || r.orderFlowContext != null;
    }

    private List<String> mergeFactors(AnalysisResult r) {
        List<String> all = new ArrayList<>(r.scoreFactors);
        if (r.plan != null) {
            for (String f : r.plan.factors) {
                if (!all.contains(f)) all.add(f);
            }
        }
        return all;
    }

    private List<String> mergeWarnings(AnalysisResult r, TradePlan plan) {
        List<String> all = new ArrayList<>(r.warnings);
        if (plan != null) {
            for (String w : plan.warnings) {
                if (!all.contains(w)) all.add(w);
            }
        }
        return all;
    }

    private Forecast noData() {
        if (I18n.english()) {
            return new Forecast("-", "-", "STAND ASIDE", "-", 0,
                    cssHeader() +
                    "<div style='padding:30px;text-align:center;'>" +
                    "<div style='font-size:40px;margin-bottom:12px;'>AI</div>" +
                    "<div style='color:#f07070;font-size:16px;font-weight:bold;'>No analysis data</div>" +
                    "<div style='color:#7a7f99;font-size:13px;margin-top:8px;'>Run analysis for the current symbol first.</div>" +
                    "</div></body></html>");
        }
        return new Forecast("—", "—", "ВОЗДЕРЖАТЬСЯ", "—", 0,
                cssHeader() +
                "<div style='padding:30px;text-align:center;'>" +
                "<div style='font-size:40px;margin-bottom:12px;'>🤖</div>" +
                "<div style='color:#f07070;font-size:16px;font-weight:bold;'>Нет данных для анализа</div>" +
                "<div style='color:#7a7f99;font-size:13px;margin-top:8px;'>Сначала нажмите «Анализ» для загрузки данных по текущей паре.</div>" +
                "</div></body></html>");
    }

    private String section(String title) {
        return "<h2>" + title + "</h2>";
    }

    private String row(String label, String value) {
        return "<div class='row'><span class='lbl'>" + label + "</span><span>" + value + "</span></div>";
    }

    private String val(String cls, String text) {
        return "<span class='val" + (cls.isEmpty() ? "" : " " + cls) + "'>" + text + "</span>";
    }

    private String tag(String cls, String text) {
        return "<span class='tag " + cls + "'>" + text + "</span>";
    }

    private String heroHeader(AnalysisResult r,
                              TradePlan plan,
                              String verdict,
                              String verdictClass,
                              String quality,
                              String qualityColor,
                              int confidence,
                              String direction,
                              String directionColor,
                              boolean english) {
        Double evValue = plan == null ? null : plan.expectedValueR(RadarBacktestStats.calibratedWinRate01(r, plan));
        String ev = evValue == null ? "-" : signed(fmt2(evValue) + "R");
        String entryStatus;
        String entryClass;
        if (plan == null) {
            entryStatus = english ? "No plan" : "Нет плана";
            entryClass = "skip";
        } else if (plan.entryConfirmed) {
            entryStatus = english ? "Confirmed" : "Подтвержден";
            entryClass = "enter";
        } else {
            entryStatus = english ? "Waiting" : "Ждем";
            entryClass = "wait";
        }
        String title = english ? "AI Forecast" : "ИИ прогноз";
        String subtitle = english
                ? "Internal SMC engine, liquidity, order flow and risk filters"
                : "SMC, ликвидность, order flow и риск-фильтры в одном решении";

        StringBuilder h = new StringBuilder();
        h.append("<div class='hero'>");
        h.append("<table class='heroTable' cellspacing='0' cellpadding='0'><tr>");
        h.append("<td class='heroMain'>")
                .append("<div class='kicker'>").append(title).append("</div>")
                .append("<div class='heroTitle'>").append(esc(r.symbol))
                .append("<span>").append(esc(r.ltfInterval)).append("</span></div>")
                .append("<div class='heroSub'>").append(subtitle).append("</div>")
                .append("</td>");
        h.append("<td class='heroDecision'><div class='decision ").append(verdictClass).append("'>")
                .append(esc(verdict)).append("</div></td>");
        h.append("</tr></table>");
        h.append("<table class='metricGrid' cellspacing='0' cellpadding='0'><tr>");
        h.append(metricCell(english ? "Direction" : "Направление",
                "<span style='color:" + directionColor + ";'>" + esc(direction) + "</span>", ""));
        h.append(metricCell("SMC", r.smcScore + "/100", scoreClass(r.smcScore)));
        h.append(metricCell(english ? "Quality" : "Качество",
                "<span style='color:" + qualityColor + ";'>" + esc(quality) + "</span>", ""));
        h.append(metricCell(english ? "Confidence" : "Уверенность", confidence + "%", confidenceClass(confidence)));
        h.append(metricCell("EV", ev, evValue != null && evValue > 0.0 ? "good" : "warn"));
        h.append(metricCell(english ? "Entry" : "Вход", entryStatus, entryClass));
        h.append("</tr></table>");
        h.append("</div>");
        return h.toString();
    }

    private String metricCell(String label, String value, String cls) {
        return "<td><div class='metric " + cls + "'><div class='metricLabel'>"
                + esc(label) + "</div><div class='metricValue'>" + value + "</div></div></td>";
    }

    private String verdictClass(String verdict) {
        return switch (verdict) {
            case "ВХОДИТЬ", "ENTER" -> "enter";
            case "ЖДАТЬ", "WAIT" -> "wait";
            default -> "skip";
        };
    }

    private String qualityColor(String quality) {
        return switch (quality) {
            case "A+" -> "#4caf78";
            case "A" -> "#7ab8ff";
            case "B" -> "#ffb347";
            default -> "#f07070";
        };
    }

    private String scoreClass(int score) {
        if (score >= 70) return "good";
        if (score >= 50) return "warn";
        return "skip";
    }

    private String confidenceClass(int confidence) {
        if (confidence >= 70) return "good";
        if (confidence >= 45) return "warn";
        return "skip";
    }

    private String signed(String value) {
        if (value == null || value.isBlank() || value.startsWith("-")) return value;
        return "+" + value;
    }

    private String directionRu(Direction d) {
        if (d == null) return "Нейтральный";
        return switch (d) {
            case BULLISH -> "Бычий";
            case BEARISH -> "Медвежий";
            default -> "Нейтральный";
        };
    }

    private String directionEn(Direction d) {
        if (d == null) return "Neutral";
        return switch (d) {
            case BULLISH -> "Bullish";
            case BEARISH -> "Bearish";
            default -> "Neutral";
        };
    }

    private String regimeRu(MarketRegime mr) {
        return switch (mr.type) {
            case TREND     -> "Тренд " + directionRu(mr.direction);
            case RANGE     -> "Диапазон";
            case EXPANSION -> "Расширение " + directionRu(mr.direction);
            case CHOP      -> "Чоп (боковик)";
        };
    }

    private String zoneTypeRu(String type) {
        if (type == null) return "Зона";
        return switch (type.toUpperCase()) {
            case "OB"  -> "Order Block";
            case "FVG" -> "FVG (имбаланс)";
            case "BOS" -> "BOS зона";
            case "VOB" -> "Volume OB";
            default    -> type;
        };
    }

    private String pct(double v) {
        return String.format("%.1f%%", v * 100);
    }

    private String fmt1(double v) {
        return String.format("%.1f", v);
    }

    private String fmt2(double v) {
        return String.format("%.2f", v);
    }

    private String fmtPrice(double v) {
        if (!Double.isFinite(v)) return "—";
        if (v >= 10_000) return String.format("%.1f", v);
        if (v >= 1_000)  return String.format("%.2f", v);
        if (v >= 1)      return String.format("%.4f", v);
        return String.format("%.6f", v);
    }

    private String priceRange(double bottom, double top, double current) {
        return fmtPrice(bottom) + " – " + fmtPrice(top) +
                " <span style='color:#7a7f99;font-size:11px;'>(цена: " + fmtPrice(current) + ")</span>";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── CSS ──────────────────────────────────────────────────────────────────

    private String cssHeader() {
        String bg = hex(Colors.BG);
        String card = hex(Colors.CARD_BG);
        String field = hex(Colors.FIELD_BG);
        String grid = hex(Colors.GRID);
        String fg = hex(Colors.FG);
        String muted = hex(Colors.MUTED);
        String accent = hex(Colors.ACCENT);
        return """
                <html><head><style>
                body  { font-family:'Roboto','Segoe UI Variable Text','Segoe UI',Arial,sans-serif; font-size:13px; color:%s; background:%s; margin:12px 14px; }
                .hero { background:%s; border:1px solid %s; border-radius:8px; padding:13px 14px 12px 14px; margin-bottom:12px; }
                .heroTable { width:100%%; }
                .heroMain { vertical-align:top; }
                .heroDecision { vertical-align:top; text-align:right; width:150px; }
                .kicker { color:%s; font-size:10px; font-weight:bold; letter-spacing:1px; text-transform:uppercase; margin-bottom:4px; }
                .heroTitle { color:%s; font-size:22px; font-weight:bold; line-height:1.1; }
                .heroTitle span { color:%s; font-size:12px; font-weight:normal; margin-left:8px; }
                .heroSub { color:%s; font-size:12px; margin-top:5px; }
                .decision { display:inline-block; min-width:118px; text-align:center; padding:7px 10px; border-radius:6px; font-size:12px; font-weight:bold; letter-spacing:.3px; }
                .decision.enter { background:#17392b; border:1px solid #2e7d52; color:#5ed59c; }
                .decision.wait  { background:#352b10; border:1px solid #7a6020; color:#ffcb6b; }
                .decision.skip  { background:#37191d; border:1px solid #7a3030; color:#ff8a80; }
                .metricGrid { width:100%%; margin-top:10px; }
                .metricGrid td { padding:4px 5px 0 0; width:16%%; }
                .metric { background:%s; border:1px solid %s; border-radius:6px; padding:7px 8px; }
                .metricLabel { color:%s; font-size:10px; text-transform:uppercase; letter-spacing:.4px; }
                .metricValue { color:%s; font-size:13px; font-weight:bold; margin-top:2px; }
                .metric.good .metricValue { color:#5ed59c; }
                .metric.warn .metricValue { color:#ffcb6b; }
                .metric.skip .metricValue { color:#ff8a80; }
                h2    { color:%s; font-size:12px; margin:15px 0 6px 0; border-bottom:1px solid %s; padding-bottom:5px; text-transform:uppercase; letter-spacing:.6px; }
                .row  { background:%s; border-left:3px solid %s; margin:4px 0; padding:6px 8px; border-radius:5px; }
                .lbl  { display:inline-block; color:%s; min-width:178px; font-size:12px; vertical-align:top; }
                .val  { font-weight:bold; color:%s; font-family:'Roboto Mono','Cascadia Mono','Consolas',monospace; }
                .val.good   { color:#5ed59c; }
                .val.warn   { color:#ffcb6b; }
                .val.bad    { color:#ff8a80; }
                .val.purple { color:#d0a7ff; }
                .val.cyan   { color:#64dbe6; }
                .tag  { display:inline-block; padding:2px 7px; border-radius:5px; font-size:11px; font-weight:bold; margin:1px 2px; }
                .tag.green  { background:#17392b; color:#5ed59c; }
                .tag.yellow { background:#352b10; color:#ffcb6b; }
                .tag.red    { background:#37191d; color:#ff8a80; }
                .tag.purple { background:#281d45; color:#d0a7ff; }
                .tag.blue   { background:#17233f; color:#8abfff; }
                .verdict    { padding:11px 14px; border-radius:7px; margin:10px 0 7px 0; font-size:14px; font-weight:bold; }
                .verdict.enter { background:#17392b; border:1px solid #2e7d52; color:#5ed59c; }
                .verdict.wait  { background:#352b10; border:1px solid #7a6020; color:#ffcb6b; }
                .verdict.skip  { background:#37191d; border:1px solid #7a3030; color:#ff8a80; }
                .summary { background:%s; border:1px solid %s; color:%s; font-size:12px; line-height:1.65; margin-top:6px; padding:9px 10px; border-radius:6px; }
                .warn-item { background:#352b10; border-left:3px solid #ffcb6b; color:#ffcb6b; font-size:12px; margin:4px 0; padding:6px 8px; border-radius:5px; }
                .factor    { background:#17233f; border-left:3px solid #7ab8ff; color:#9cc8ff; font-size:12px; margin:4px 0; padding:6px 8px; border-radius:5px; }
                </style></head><body>
                """.formatted(fg, bg, card, grid, accent, fg, muted, muted,
                field, grid, muted, fg, accent, grid, field, accent, muted, fg,
                field, grid, fg);
    }

    private String hex(Color color) {
        Color safe = color == null ? Color.WHITE : color;
        return String.format("#%02X%02X%02X", safe.getRed(), safe.getGreen(), safe.getBlue());
    }
}
