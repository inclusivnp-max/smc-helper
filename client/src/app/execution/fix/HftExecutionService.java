package app.execution.fix;

import app.execution.algo.ExecutionAlgoEngine;
import app.execution.algo.ExecutionSlice;
import app.execution.algo.SlicedExecutionPlan;
import app.infrastructure.LatencySnapshot;
import app.infrastructure.NetworkLatencyManager;
import app.model.TradePlan;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class HftExecutionService {
    private final ExecutionGateway gateway;
    private final RiskGate riskGate;
    private final ExecutionAlgoEngine algoEngine = new ExecutionAlgoEngine();
    private final NetworkLatencyManager latencyManager = new NetworkLatencyManager();
    private final ExecutionLedger ledger = new ExecutionLedger();
    private final ExecutionJournal journal;
    private final Map<String, List<String>> protectiveOrderIdsBySymbol = new ConcurrentHashMap<>();
    private ScheduledExecutorService reconciler;
    private boolean autoProtectiveOrders = true;

    public HftExecutionService(ExecutionGateway gateway, RiskGate riskGate) {
        this(gateway, riskGate, ExecutionJournal.defaultJournal());
    }

    public HftExecutionService(ExecutionGateway gateway, RiskGate riskGate, ExecutionJournal journal) {
        this.gateway = gateway;
        this.riskGate = riskGate;
        this.journal = journal == null ? ExecutionJournal.defaultJournal() : journal;
        this.journal.replayInto(ledger);
        ExecutionJournal.DailyRiskStats stats = this.journal.dailyRiskStats();
        this.riskGate.restoreDailyStats(stats.day, stats.realizedPnl, stats.trades, stats.consecutiveLosses);
    }

    public static HftExecutionService paper() {
        RiskSettings settings = RiskSettings.load();
        HftExecutionService service = new HftExecutionService(new PaperFixGateway(), settings.toRiskGate());
        service.setAutoProtectiveOrders(settings.autoProtectiveOrders);
        return service;
    }

    public ExecutionReport submitEntry(String symbol, TradePlan plan, double quantity) throws Exception {
        return submitEntry(symbol, plan, quantity, ExecutionOrder.TimeInForce.IOC);
    }

    public ExecutionReport submitEntry(String symbol, TradePlan plan, double quantity,
                                       ExecutionOrder.TimeInForce timeInForce) throws Exception {
        return submitEntry(symbol, plan, quantity, timeInForce, ExecutionOrder.Type.LIMIT);
    }

    public ExecutionReport submitEntry(String symbol, TradePlan plan, double quantity,
                                       ExecutionOrder.TimeInForce timeInForce,
                                       ExecutionOrder.Type orderType) throws Exception {
        String clientOrderId = "SMC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        ExecutionOrder.Type effectiveType = orderType == null ? ExecutionOrder.Type.LIMIT : orderType;
        ExecutionOrder.TimeInForce effectiveTif = effectiveType == ExecutionOrder.Type.MARKET
                ? ExecutionOrder.TimeInForce.IOC
                : (timeInForce == null ? ExecutionOrder.TimeInForce.IOC : timeInForce);
        ExecutionOrder order = new ExecutionOrder(
                clientOrderId,
                symbol,
                plan.longSide ? ExecutionOrder.Side.BUY : ExecutionOrder.Side.SELL,
                effectiveType,
                effectiveTif,
                quantity,
                plan.entry,
                0.0,
                String.format(Locale.US, "smc=%d setup=%s", plan.confidence, plan.setupType)
        );
        try {
            order = normalize(order);
        } catch (OrderNormalizationException ex) {
            ExecutionReport rejected = new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, ex.getMessage());
            journal.recordExecutionReport(rejected, gateway.venue());
            return rejected;
        }
        RiskGate.Validation validation = riskGate.validate(order, plan);
        if (!validation.accepted) {
            ExecutionReport rejected = new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, validation.reason);
            journal.recordExecutionReport(rejected, gateway.venue());
            return rejected;
        }
        ExecutionReport stateReject = validateOrderState(order, plan);
        if (stateReject != null) {
            journal.recordExecutionReport(stateReject, gateway.venue());
            return stateReject;
        }
        if (!gateway.isConnected()) {
            ExecutionReport rejected = new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "Gateway disconnected");
            journal.recordExecutionReport(rejected, gateway.venue());
            return rejected;
        }
        registerOrder(order, plan);
        ExecutionReport report;
        try {
            report = gateway.send(order);
        } catch (Exception ex) {
            report = new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        applyExecutionReport(report);
        recordAcceptedEntry(report);
        return report;
    }

    public SlicedExecutionPlan buildExecutionPlan(TradePlan plan, double quantity, String algorithm) {
        double notional = Math.abs(quantity * plan.entry);
        String mode = algorithm == null ? "AUTO" : algorithm.trim().toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "TWAP" -> algoEngine.twap(plan, quantity, Math.max(6, (int) Math.ceil(notional / 2_000.0)), 60_000L);
            case "VWAP" -> algoEngine.vwap(plan, quantity, null, 90_000L);
            case "ICEBERG" -> algoEngine.iceberg(plan, quantity, Math.max(quantity / 50.0, 0.00000001));
            default -> algoEngine.auto(plan, quantity, notional);
        };
    }

    public List<ExecutionReport> submitSlicedEntry(String symbol, TradePlan plan,
                                                   double quantity, String algorithm) throws Exception {
        SlicedExecutionPlan executionPlan = buildExecutionPlan(plan, quantity, algorithm);
        List<ExecutionReport> reports = new ArrayList<>();
        LatencySnapshot latency = latencyManager.checkBinanceFutures();
        if (!latency.safeForHft) {
            reports.add(new ExecutionReport("SMC-LATENCY-GATE", "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "Execution paused by latency gate: " + latency.message
                    + " / RTT " + latency.rttMs + "ms"));
            return reports;
        }
        for (ExecutionSlice slice : executionPlan.slices) {
            latency = latencyManager.checkBinanceFutures();
            if (!latency.safeForHft) {
                reports.add(new ExecutionReport("SMC-LATENCY-GATE", "", ExecutionReport.Status.REJECTED,
                        0.0, 0.0, "Remaining slices paused by latency gate: " + latency.message
                        + " / RTT " + latency.rttMs + "ms"));
                break;
            }
            String clientOrderId = "SMC-" + slice.tag + "-" + slice.index + "-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            ExecutionOrder order = new ExecutionOrder(
                    clientOrderId,
                    symbol,
                    plan.longSide ? ExecutionOrder.Side.BUY : ExecutionOrder.Side.SELL,
                    ExecutionOrder.Type.LIMIT,
                    ExecutionOrder.TimeInForce.GTC,
                    slice.quantity,
                    slice.limitPrice,
                    0.0,
                    String.format(Locale.US, "smc=%d setup=%s algo=%s slice=%d/%d",
                            plan.confidence, plan.setupType, executionPlan.algorithm,
                            slice.index, executionPlan.slices.size())
            );
            try {
                order = normalize(order);
            } catch (OrderNormalizationException ex) {
                reports.add(new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                        0.0, 0.0, ex.getMessage()));
                continue;
            }
            RiskGate.Validation validation = riskGate.validate(order, plan);
            if (!validation.accepted) {
                reports.add(new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                        0.0, 0.0, validation.reason));
                continue;
            }
            ExecutionReport stateReject = validateOrderState(order, plan);
            if (stateReject != null) {
                reports.add(stateReject);
                journal.recordExecutionReport(stateReject, gateway.venue());
                continue;
            }
            if (!gateway.isConnected()) {
                reports.add(new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                        0.0, 0.0, "Gateway disconnected"));
                continue;
            }
            registerOrder(order, plan);
            ExecutionReport report;
            try {
                report = gateway.send(order);
            } catch (Exception ex) {
                report = new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                        0.0, 0.0, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            }
            applyExecutionReport(report);
            recordAcceptedEntry(report);
            reports.add(report);
        }
        return reports;
    }

    public ExecutionReport cancel(String clientOrderId) throws Exception {
        ExecutionReport report = gateway.cancel(clientOrderId);
        applyExecutionReport(report);
        return report;
    }

    public void onExecutionReport(ExecutionReport report) {
        applyExecutionReport(report);
    }

    public List<ProtectiveOrderPlan> protectivePlans() {
        return ledger.protectivePlans();
    }

    public ProtectiveOrderPlan protectivePlan(String symbol) {
        return ledger.protectivePlan(symbol);
    }

    public List<ExecutionLedger.LocalOrderState> localOpenOrders() {
        return ledger.localOpenOrders();
    }

    public void recordRealizedPnl(double pnl) {
        riskGate.recordRealizedPnl(pnl);
        journal.recordRiskEvent("realized_pnl", Double.toString(pnl), riskGate.status());
    }

    public void tripKillSwitch(String reason) {
        riskGate.tripKillSwitch(reason);
        journal.recordRiskEvent("kill_switch_trip", reason, riskGate.status());
    }

    public void resetKillSwitch() {
        riskGate.resetKillSwitch();
        journal.recordRiskEvent("kill_switch_reset", "", riskGate.status());
    }

    public RiskGate.RiskStatus riskStatus() {
        return riskGate.status();
    }

    public ExecutionJournal.JournalSummary journalSummary() {
        return journal.summary();
    }

    public ExecutionJournal.ReplayResult restoreFromJournal() {
        return journal.replayInto(ledger);
    }

    public void setAutoProtectiveOrders(boolean enabled) {
        autoProtectiveOrders = enabled;
    }

    public ExecutionLedger.ReconciliationResult reconcileOpenOrders(boolean cancelOrphans) throws Exception {
        ExecutionLedger.ReconciliationResult result = ledger.compareOpenOrders(gateway.openOrders());
        if (cancelOrphans) {
            for (OpenOrderSnapshot orphan : result.orphanedOnExchange) {
                try {
                    ExecutionReport cancelReport = gateway.cancel(orphan.clientOrderId);
                    applyExecutionReport(cancelReport);
                    result.cancelReports.add(cancelReport);
                } catch (Exception ex) {
                    result.warnings.add("cancel orphan failed: " + orphan.clientOrderId + " - " + ex.getMessage());
                }
            }
        }
        journal.recordReconciliation(result, gateway.venue());
        return result;
    }

    public synchronized void startReconciler() {
        startReconciler(Duration.ofMinutes(1), true, null);
    }

    public synchronized void startReconciler(Duration interval,
                                            boolean cancelOrphans,
                                            Consumer<ExecutionLedger.ReconciliationResult> listener) {
        stopReconciler();
        long periodMs = Math.max(5_000L, interval == null ? 60_000L : interval.toMillis());
        reconciler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "SMC-OpenOrders-Reconciler");
            thread.setDaemon(true);
            return thread;
        });
        reconciler.scheduleWithFixedDelay(() -> {
            try {
                ExecutionLedger.ReconciliationResult result = reconcileOpenOrders(cancelOrphans);
                if (listener != null) listener.accept(result);
            } catch (Exception ex) {
                ExecutionLedger.ReconciliationResult result = new ExecutionLedger.ReconciliationResult();
                result.warnings.add("reconcile failed: " + ex.getMessage());
                if (listener != null) listener.accept(result);
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void stopReconciler() {
        if (reconciler != null) {
            reconciler.shutdownNow();
            reconciler = null;
        }
    }

    private void registerOrder(ExecutionOrder order, TradePlan plan) {
        ledger.register(order, plan);
        journal.recordOrderRegistered(order, plan, gateway.venue());
    }

    private ExecutionReport validateOrderState(ExecutionOrder order, TradePlan plan) {
        if (order == null || plan == null || order.protectiveOrder()) return null;
        if (order.strategyTag != null && order.strategyTag.contains("algo=")) return null;
        long lockMs = riskGate.duplicateOrderLockSeconds * 1_000L;
        ExecutionLedger.LocalOrderState duplicate = ledger.activeEntryOrder(order.symbol, plan.longSide, lockMs);
        if (duplicate != null) {
            return new ExecutionReport(order.clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "Order state lock: active "
                    + duplicate.order.side + " " + duplicate.order.symbol
                    + " order is still " + duplicate.status
                    + " (" + duplicate.order.clientOrderId + ")");
        }
        if (riskGate.maxOpenPositions > 0
                && !ledger.hasOpenPosition(order.symbol, plan.longSide)
                && ledger.openPositionCount() >= riskGate.maxOpenPositions) {
            return new ExecutionReport(order.clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "Max open positions reached: "
                    + ledger.openPositionCount() + "/" + riskGate.maxOpenPositions);
        }
        return null;
    }

    private void recordAcceptedEntry(ExecutionReport report) {
        if (report == null) return;
        if (report.status == ExecutionReport.Status.REJECTED
                || report.status == ExecutionReport.Status.CANCELLED) {
            return;
        }
        ExecutionLedger.LocalOrderState state = ledger.orderState(report.clientOrderId);
        if (state == null || state.protectiveOrder()) return;
        if (state.order.strategyTag != null && state.order.strategyTag.contains("algo=")) return;
        riskGate.recordTradeAccepted();
        journal.recordRiskEvent("trade_accepted", report.clientOrderId, riskGate.status());
    }

    private void applyExecutionReport(ExecutionReport report) {
        ledger.applyReport(report);
        journal.recordExecutionReport(report, gateway.venue());
        if (autoProtectiveOrders) refreshProtectiveOrdersAfterFill(report);
    }

    private void refreshProtectiveOrdersAfterFill(ExecutionReport report) {
        if (report == null || report.filledQuantity <= 0.0) return;
        ExecutionLedger.LocalOrderState state = ledger.orderState(report.clientOrderId);
        if (state == null || state.plan == null || state.protectiveOrder()) return;
        if (report.status != ExecutionReport.Status.PARTIALLY_FILLED
                && report.status != ExecutionReport.Status.FILLED) {
            return;
        }
        ProtectiveOrderPlan plan = ledger.protectivePlan(state.order.symbol);
        if (plan == null || !plan.active() || !gateway.isConnected()) return;
        journal.recordProtectivePlan(plan, "entry fill " + report.status);

        List<String> previousIds = protectiveOrderIdsBySymbol.getOrDefault(plan.symbol, List.of());
        for (String clientOrderId : previousIds) {
            try {
                applyExecutionReport(gateway.cancel(clientOrderId));
            } catch (Exception ex) {
                journal.recordRiskEvent("protective_cancel_failed",
                        clientOrderId + " " + ex.getMessage(), riskGate.status());
            }
        }

        String groupId = "SMC-PROTECT-" + plan.symbol + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        List<String> submitted = new ArrayList<>();
        for (ExecutionOrder protectiveOrder : plan.buildOrders(groupId)) {
            try {
                ExecutionOrder normalized = normalize(protectiveOrder);
                registerOrder(normalized, state.plan);
                ExecutionReport protectiveReport = gateway.send(normalized);
                ledger.applyReport(protectiveReport);
                journal.recordExecutionReport(protectiveReport, gateway.venue());
                submitted.add(normalized.clientOrderId);
            } catch (Exception ex) {
                journal.recordRiskEvent("protective_submit_failed",
                        protectiveOrder.clientOrderId + " " + ex.getMessage(), riskGate.status());
            }
        }
        protectiveOrderIdsBySymbol.put(plan.symbol, submitted);
    }

    private ExecutionOrder normalize(ExecutionOrder order) throws Exception {
        OrderNormalization normalization = gateway.normalizeOrder(order);
        if (normalization == null || normalization.order == null) return order;
        if (normalization.changed && !normalization.message.isBlank()) {
            journal.recordRiskEvent("order_normalized", normalization.message, riskGate.status());
        }
        return normalization.order;
    }
}
