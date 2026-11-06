package app.execution.fix;

import app.common.Json;
import app.common.ProjectPaths;
import app.model.TradePlan;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ExecutionJournal {
    private final Path path;

    public ExecutionJournal(Path directory) {
        this.path = directory.resolve("events.jsonl");
    }

    public static ExecutionJournal defaultJournal() {
        return new ExecutionJournal(ProjectPaths.executionJournalDir());
    }

    public Path path() {
        return path;
    }

    public synchronized void recordOrderRegistered(ExecutionOrder order, TradePlan plan, String venue) {
        if (order == null) return;
        append("{\"type\":\"order_registered\",\"time\":" + System.currentTimeMillis()
                + ",\"venue\":\"" + esc(venue) + "\""
                + ",\"order\":" + orderJson(order)
                + ",\"plan\":" + planJson(plan)
                + "}");
    }

    public synchronized void recordExecutionReport(ExecutionReport report, String venue) {
        if (report == null) return;
        append("{\"type\":\"execution_report\",\"time\":" + report.time
                + ",\"venue\":\"" + esc(venue) + "\""
                + ",\"report\":" + reportJson(report)
                + "}");
    }

    public synchronized void recordProtectivePlan(ProtectiveOrderPlan plan, String reason) {
        if (plan == null || !plan.active()) return;
        append("{\"type\":\"protective_plan\",\"time\":" + System.currentTimeMillis()
                + ",\"reason\":\"" + esc(reason) + "\""
                + ",\"plan\":" + protectivePlanJson(plan)
                + "}");
    }

    public synchronized void recordReconciliation(ExecutionLedger.ReconciliationResult result, String venue) {
        if (result == null) return;
        append("{\"type\":\"open_orders_reconcile\",\"time\":" + System.currentTimeMillis()
                + ",\"venue\":\"" + esc(venue) + "\""
                + ",\"clean\":" + result.clean()
                + ",\"orphans\":" + result.orphanedOnExchange.size()
                + ",\"missing\":" + result.missingOnExchange.size()
                + ",\"quantityMismatches\":" + result.quantityMismatches.size()
                + ",\"cancelReports\":" + result.cancelReports.size()
                + ",\"warnings\":" + result.warnings.size()
                + ",\"summary\":\"" + esc(result.summary()) + "\""
                + "}");
    }

    public synchronized void recordRiskEvent(String action, String reason, RiskGate.RiskStatus status) {
        append("{\"type\":\"risk_event\",\"time\":" + System.currentTimeMillis()
                + ",\"action\":\"" + esc(action) + "\""
                + ",\"reason\":\"" + esc(reason) + "\""
                + ",\"status\":\"" + esc(status == null ? "" : status.summary()) + "\""
                + "}");
    }

    public synchronized void recordManualPositionClose(String symbol,
                                                       boolean longSide,
                                                       double entryPrice,
                                                       double quantity,
                                                       double closePrice,
                                                       String reason) {
        append("{\"type\":\"manual_position_close\",\"time\":" + System.currentTimeMillis()
                + ",\"symbol\":\"" + esc(symbol) + "\""
                + ",\"longSide\":" + longSide
                + ",\"entryPrice\":" + num(entryPrice)
                + ",\"quantity\":" + num(quantity)
                + ",\"closePrice\":" + num(closePrice)
                + ",\"reason\":\"" + esc(reason) + "\""
                + "}");
    }

    public synchronized ReplayResult replayInto(ExecutionLedger ledger) {
        ReplayResult result = new ReplayResult();
        if (ledger == null || !Files.exists(path)) return result;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                result.events++;
                try {
                    Map<String, Object> event = Json.asObject(Json.parse(line));
                    String type = Json.string(event.get("type"), "");
                    if ("order_registered".equals(type)) {
                        ExecutionOrder order = parseOrder(Json.asObject(event.get("order")));
                        Object planValue = event.get("plan");
                        TradePlan plan = planValue == null ? null : parsePlan(Json.asObject(planValue));
                        ledger.register(order, plan);
                        result.orders++;
                    } else if ("execution_report".equals(type)) {
                        ledger.applyReport(parseReport(Json.asObject(event.get("report"))));
                        result.reports++;
                    } else if ("manual_position_close".equals(type)) {
                        ledger.closePosition(
                                Json.string(event.get("symbol"), ""),
                                Json.bool(event.get("longSide"), true),
                                Json.number(event.get("entryPrice"), 0.0),
                                Json.number(event.get("quantity"), 0.0));
                        result.reports++;
                    }
                } catch (Exception ex) {
                    result.errors++;
                }
            }
        } catch (IOException ex) {
            result.errors++;
        }
        return result;
    }

    public synchronized JournalSummary summary() {
        JournalSummary summary = new JournalSummary(path);
        if (!Files.exists(path)) return summary;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                summary.events++;
                try {
                    Map<String, Object> event = Json.asObject(Json.parse(line));
                    String type = Json.string(event.get("type"), "");
                    switch (type) {
                        case "order_registered" -> summary.orders++;
                        case "execution_report" -> summary.reports++;
                        case "protective_plan" -> summary.protectivePlans++;
                        case "risk_event" -> summary.riskEvents++;
                        case "open_orders_reconcile" -> summary.reconciliations++;
                        default -> summary.other++;
                    }
                } catch (Exception ex) {
                    summary.parseErrors++;
                }
            }
            summary.bytes = Files.size(path);
        } catch (IOException ex) {
            summary.parseErrors++;
        }
        return summary;
    }

    public synchronized DailyRiskStats dailyRiskStats() {
        DailyRiskStats stats = new DailyRiskStats(LocalDate.now(ZoneId.systemDefault()));
        if (!Files.exists(path)) return stats;
        Map<String, Boolean> protectiveByClientId = new HashMap<>();
        Set<String> acceptedEntryIds = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    Map<String, Object> event = Json.asObject(Json.parse(line));
                    long time = Json.integer(event.get("time"), 0L);
                    if (time <= 0L || !stats.day.equals(Instant.ofEpochMilli(time)
                            .atZone(ZoneId.systemDefault()).toLocalDate())) {
                        continue;
                    }
                    String type = Json.string(event.get("type"), "");
                    if ("order_registered".equals(type)) {
                        ExecutionOrder order = parseOrder(Json.asObject(event.get("order")));
                        if (order != null && order.clientOrderId != null && !order.clientOrderId.isBlank()) {
                            protectiveByClientId.put(order.clientOrderId, order.protectiveOrder());
                        }
                    } else if ("execution_report".equals(type)) {
                        ExecutionReport report = parseReport(Json.asObject(event.get("report")));
                        if (report != null && report.clientOrderId != null && !report.clientOrderId.isBlank()) {
                            boolean protective = protectiveByClientId.getOrDefault(report.clientOrderId, false);
                            if (!protective
                                    && report.status != ExecutionReport.Status.REJECTED
                                    && report.status != ExecutionReport.Status.CANCELLED) {
                                acceptedEntryIds.add(report.clientOrderId);
                            }
                        }
                    } else if ("manual_position_close".equals(type)) {
                        boolean longSide = Json.bool(event.get("longSide"), true);
                        double entry = Json.number(event.get("entryPrice"), 0.0);
                        double close = Json.number(event.get("closePrice"), 0.0);
                        double quantity = Json.number(event.get("quantity"), 0.0);
                        if (entry <= 0.0 || close <= 0.0 || quantity <= 0.0) continue;
                        double pnl = (longSide ? close - entry : entry - close) * quantity;
                        stats.realizedPnl += pnl;
                        if (pnl < -0.00000001) stats.consecutiveLosses++;
                        else if (pnl > 0.00000001) stats.consecutiveLosses = 0;
                    }
                } catch (Exception ex) {
                    stats.errors++;
                }
            }
        } catch (IOException ex) {
            stats.errors++;
        }
        stats.trades = acceptedEntryIds.size();
        return stats;
    }

    public synchronized AccountSnapshot replayedAccountSnapshot(String venue) {
        return replayedAccountSnapshot(venue, Map.of(), 10_000.0);
    }

    public synchronized AccountSnapshot replayedAccountSnapshot(String venue,
                                                               Map<String, Double> markPrices,
                                                               double paperBalance) {
        ExecutionLedger ledger = new ExecutionLedger();
        replayInto(ledger);
        List<OpenPositionSnapshot> positions = ledger.openPositions(markPrices);
        double unrealized = 0.0;
        for (OpenPositionSnapshot position : positions) {
            unrealized += position.unrealizedPnl;
        }
        double initialWallet = paperBalance > 0.0 ? paperBalance : 10_000.0;
        double wallet = initialWallet + realizedPaperPnl();
        double available = Math.max(0.0, wallet - ledger.openMargin());
        return new AccountSnapshot(
                venue == null || venue.isBlank() ? "PAPER-JOURNAL" : venue,
                "USDT",
                wallet,
                available,
                wallet + unrealized,
                unrealized,
                ledger.localOpenOrderSnapshots(),
                positions,
                System.currentTimeMillis());
    }

    private double realizedPaperPnl() {
        if (!Files.exists(path)) return 0.0;
        double pnl = 0.0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    Map<String, Object> event = Json.asObject(Json.parse(line));
                    if (!"manual_position_close".equals(Json.string(event.get("type"), ""))) continue;
                    boolean longSide = Json.bool(event.get("longSide"), true);
                    double entry = Json.number(event.get("entryPrice"), 0.0);
                    double close = Json.number(event.get("closePrice"), 0.0);
                    double quantity = Json.number(event.get("quantity"), 0.0);
                    if (entry <= 0.0 || close <= 0.0 || quantity <= 0.0) continue;
                    pnl += (longSide ? close - entry : entry - close) * quantity;
                } catch (Exception ignored) {
                    // Bad journal lines are ignored here the same way replayInto keeps the UI alive.
                }
            }
        } catch (IOException ignored) {
            return pnl;
        }
        return pnl;
    }

    private void append(String line) {
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException ignored) {
            // Execution must not be blocked by journal I/O. The UI can inspect missing journal files.
        }
    }

    private static String orderJson(ExecutionOrder order) {
        return "{"
                + "\"clientOrderId\":\"" + esc(order.clientOrderId) + "\""
                + ",\"symbol\":\"" + esc(order.symbol) + "\""
                + ",\"side\":\"" + order.side + "\""
                + ",\"type\":\"" + order.type + "\""
                + ",\"timeInForce\":\"" + order.timeInForce + "\""
                + ",\"quantity\":" + num(order.quantity)
                + ",\"price\":" + num(order.price)
                + ",\"stopPrice\":" + num(order.stopPrice)
                + ",\"createdAt\":" + order.createdAt
                + ",\"strategyTag\":\"" + esc(order.strategyTag) + "\""
                + "}";
    }

    private static String reportJson(ExecutionReport report) {
        return "{"
                + "\"clientOrderId\":\"" + esc(report.clientOrderId) + "\""
                + ",\"exchangeOrderId\":\"" + esc(report.exchangeOrderId) + "\""
                + ",\"status\":\"" + report.status + "\""
                + ",\"filledQuantity\":" + num(report.filledQuantity)
                + ",\"remainingQuantity\":" + num(report.remainingQuantity)
                + ",\"avgPrice\":" + num(report.avgPrice)
                + ",\"message\":\"" + esc(report.message) + "\""
                + ",\"time\":" + report.time
                + "}";
    }

    private static String planJson(TradePlan plan) {
        if (plan == null) return "null";
        return "{"
                + "\"longSide\":" + plan.longSide
                + ",\"entry\":" + num(plan.entry)
                + ",\"stop\":" + num(plan.stop)
                + ",\"tp1\":" + num(plan.tp1)
                + ",\"tp2\":" + num(plan.tp2)
                + ",\"tp3\":" + num(plan.tp3)
                + ",\"confidence\":" + plan.confidence
                + ",\"entryConfirmed\":" + plan.entryConfirmed
                + ",\"setupType\":\"" + esc(plan.setupType) + "\""
                + "}";
    }

    private static String protectivePlanJson(ProtectiveOrderPlan plan) {
        return "{"
                + "\"symbol\":\"" + esc(plan.symbol) + "\""
                + ",\"longSide\":" + plan.longSide
                + ",\"filledQuantity\":" + num(plan.filledQuantity)
                + ",\"avgEntryPrice\":" + num(plan.avgEntryPrice)
                + ",\"stopPrice\":" + num(plan.stopPrice)
                + ",\"tp1Price\":" + num(plan.tp1Price)
                + ",\"tp2Price\":" + num(plan.tp2Price)
                + ",\"tp3Price\":" + num(plan.tp3Price)
                + ",\"stopQuantity\":" + num(plan.stopQuantity)
                + ",\"tp1Quantity\":" + num(plan.tp1Quantity)
                + ",\"tp2Quantity\":" + num(plan.tp2Quantity)
                + ",\"tp3Quantity\":" + num(plan.tp3Quantity)
                + ",\"updatedAt\":" + plan.updatedAt
                + "}";
    }

    private static ExecutionOrder parseOrder(Map<String, Object> json) {
        return new ExecutionOrder(
                Json.string(json.get("clientOrderId"), ""),
                Json.string(json.get("symbol"), ""),
                enumValue(ExecutionOrder.Side.class, Json.string(json.get("side"), "BUY"), ExecutionOrder.Side.BUY),
                enumValue(ExecutionOrder.Type.class, Json.string(json.get("type"), "LIMIT"), ExecutionOrder.Type.LIMIT),
                enumValue(ExecutionOrder.TimeInForce.class, Json.string(json.get("timeInForce"), "GTC"), ExecutionOrder.TimeInForce.GTC),
                Json.number(json.get("quantity"), 0.0),
                Json.number(json.get("price"), 0.0),
                Json.number(json.get("stopPrice"), 0.0),
                Json.string(json.get("strategyTag"), "restored"));
    }

    private static TradePlan parsePlan(Map<String, Object> json) {
        if (json == null || json.isEmpty()) return null;
        return TradePlan.restored(
                Json.bool(json.get("longSide"), true),
                Json.number(json.get("entry"), 0.0),
                Json.number(json.get("stop"), 0.0),
                Json.number(json.get("tp1"), 0.0),
                Json.number(json.get("tp2"), 0.0),
                Json.number(json.get("tp3"), 0.0),
                (int) Json.integer(json.get("confidence"), 0L),
                Json.bool(json.get("entryConfirmed"), false),
                Json.string(json.get("setupType"), "restored"));
    }

    private static ExecutionReport parseReport(Map<String, Object> json) {
        return new ExecutionReport(
                Json.string(json.get("clientOrderId"), ""),
                Json.string(json.get("exchangeOrderId"), ""),
                enumValue(ExecutionReport.Status.class, Json.string(json.get("status"), "ACCEPTED"),
                        ExecutionReport.Status.ACCEPTED),
                Json.number(json.get("filledQuantity"), 0.0),
                Json.number(json.get("remainingQuantity"), Double.NaN),
                Json.number(json.get("avgPrice"), 0.0),
                Json.string(json.get("message"), "restored"));
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String raw, T fallback) {
        try {
            return Enum.valueOf(type, raw == null ? "" : raw);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static String num(double value) {
        if (!Double.isFinite(value)) return "null";
        return String.format(Locale.US, "%.12f", value);
    }

    private static String esc(String value) {
        String text = value == null ? "" : value;
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 32) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.toString();
    }

    public static class ReplayResult {
        public int events;
        public int orders;
        public int reports;
        public int errors;

        public String summary() {
            return "replayed events=" + events + " orders=" + orders
                    + " reports=" + reports + " errors=" + errors;
        }
    }

    public static class JournalSummary {
        public final Path path;
        public int events;
        public int orders;
        public int reports;
        public int protectivePlans;
        public int reconciliations;
        public int riskEvents;
        public int other;
        public int parseErrors;
        public long bytes;

        JournalSummary(Path path) {
            this.path = path;
        }

        public List<String> lines() {
            List<String> lines = new ArrayList<>();
            lines.add("Journal: " + path);
            lines.add("Events: " + events + " / orders " + orders + " / reports " + reports
                    + " / protective " + protectivePlans);
            lines.add("Risk events: " + riskEvents + " / reconciliations " + reconciliations
                    + " / parse errors " + parseErrors);
            lines.add("Size: " + bytes + " bytes / checked " + Instant.now());
            return lines;
        }
    }

    public static class DailyRiskStats {
        public final LocalDate day;
        public double realizedPnl;
        public int trades;
        public int consecutiveLosses;
        public int errors;

        DailyRiskStats(LocalDate day) {
            this.day = day;
        }
    }
}
