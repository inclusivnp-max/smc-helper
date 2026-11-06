package app.execution.fix;

import app.model.TradePlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ExecutionLedger {
    private final Map<String, LocalOrderState> orders = new ConcurrentHashMap<>();
    private final Map<String, ManagedPosition> positions = new ConcurrentHashMap<>();

    public void register(ExecutionOrder order, TradePlan plan) {
        if (order == null || order.clientOrderId == null || order.clientOrderId.isBlank()) return;
        String positionKey = positionKey(order, plan);
        orders.put(order.clientOrderId, new LocalOrderState(order, plan, positionKey));
        positions.computeIfAbsent(positionKey, key -> new ManagedPosition(order.symbol, plan));
    }

    public void applyReport(ExecutionReport report) {
        if (report == null || report.clientOrderId == null || report.clientOrderId.isBlank()) return;
        LocalOrderState state = orders.get(report.clientOrderId);
        if (state == null) return;

        synchronized (state) {
            double cumulativeFilled = report.filledQuantity;
            if (report.status == ExecutionReport.Status.FILLED && cumulativeFilled <= 0.0) {
                cumulativeFilled = state.order.quantity;
            }
            cumulativeFilled = clamp(cumulativeFilled, 0.0, state.order.quantity);
            double deltaFilled = Math.max(0.0, cumulativeFilled - state.filledQuantity);
            state.filledQuantity = Math.max(state.filledQuantity, cumulativeFilled);
            state.remainingQuantity = Math.max(0.0, state.order.quantity - state.filledQuantity);
            state.exchangeOrderId = report.exchangeOrderId == null || report.exchangeOrderId.isBlank()
                    ? state.exchangeOrderId
                    : report.exchangeOrderId;
            state.status = report.status;
            state.lastMessage = report.message;
            state.updatedAt = report.time;

            if (deltaFilled > 0.0) {
                ManagedPosition position = positions.get(state.positionKey);
                if (position != null) {
                    if (state.protectiveOrder()) {
                        position.applyExit(deltaFilled);
                    } else {
                        double fillPrice = report.avgPrice > 0.0 ? report.avgPrice : state.order.price;
                        position.applyFill(deltaFilled, fillPrice);
                    }
                }
            }
        }
    }

    public LocalOrderState orderState(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) return null;
        LocalOrderState state = orders.get(clientOrderId);
        return state == null ? null : state.copy();
    }

    public List<LocalOrderState> localOpenOrders() {
        List<LocalOrderState> result = new ArrayList<>();
        for (LocalOrderState state : orders.values()) {
            if (state.localOpen()) result.add(state.copy());
        }
        result.sort(Comparator.comparing(s -> s.order.clientOrderId));
        return result;
    }

    public LocalOrderState activeEntryOrder(String symbol, boolean longSide, long youngerThanMs) {
        if (symbol == null || symbol.isBlank()) return null;
        long now = System.currentTimeMillis();
        for (LocalOrderState state : orders.values()) {
            synchronized (state) {
                if (!state.localOpen() || state.protectiveOrder()) continue;
                if (!symbol.equalsIgnoreCase(state.order.symbol)) continue;
                boolean sameSide = state.order.side == (longSide ? ExecutionOrder.Side.BUY : ExecutionOrder.Side.SELL);
                if (!sameSide) continue;
                long age = Math.max(0L, now - state.updatedAt);
                if (youngerThanMs <= 0L || age <= youngerThanMs) return state.copy();
            }
        }
        return null;
    }

    public boolean hasOpenPosition(String symbol, boolean longSide) {
        if (symbol == null || symbol.isBlank()) return false;
        for (ManagedPosition position : positions.values()) {
            synchronized (position) {
                if (position.filledQuantity <= 0.0) continue;
                if (symbol.equalsIgnoreCase(position.symbol) && position.longSide == longSide) return true;
            }
        }
        return false;
    }

    public int openPositionCount() {
        int count = 0;
        for (ManagedPosition position : positions.values()) {
            synchronized (position) {
                if (position.filledQuantity > 0.0) count++;
            }
        }
        return count;
    }

    public List<OpenOrderSnapshot> localOpenOrderSnapshots() {
        List<OpenOrderSnapshot> result = new ArrayList<>();
        for (LocalOrderState state : localOpenOrders()) {
            result.add(new OpenOrderSnapshot(
                    state.order.clientOrderId,
                    state.exchangeOrderId,
                    state.order.symbol,
                    state.order.side,
                    state.order.type,
                    state.order.timeInForce,
                    state.order.quantity,
                    state.filledQuantity,
                    state.remainingQuantity,
                    state.order.price,
                    state.order.stopPrice,
                    state.updatedAt));
        }
        return result;
    }

    public List<OpenPositionSnapshot> openPositions() {
        return openPositions(Map.of());
    }

    public List<OpenPositionSnapshot> openPositions(Map<String, Double> markPrices) {
        List<OpenPositionSnapshot> result = new ArrayList<>();
        for (ManagedPosition position : positions.values()) {
            String symbol = position.symbol.toUpperCase();
            double mark = markPrices == null ? 0.0 : markPrices.getOrDefault(symbol, 0.0);
            OpenPositionSnapshot snapshot = position.snapshot(mark);
            if (snapshot.quantity > 0.0) result.add(snapshot);
        }
        result.sort(Comparator.comparing(p -> p.symbol));
        return result;
    }

    public double openMargin() {
        double margin = 0.0;
        for (ManagedPosition position : positions.values()) {
            margin += position.openMargin();
        }
        return margin;
    }

    public void closePosition(String symbol, boolean longSide, double entryPrice, double quantity) {
        if (symbol == null || symbol.isBlank()) return;
        double remaining = quantity > 0.0 ? quantity : Double.MAX_VALUE;
        List<String> closedKeys = new ArrayList<>();
        for (Map.Entry<String, ManagedPosition> entry : positions.entrySet()) {
            ManagedPosition position = entry.getValue();
            if (!symbol.equalsIgnoreCase(position.symbol) || position.longSide != longSide) continue;
            if (entryPrice > 0.0 && Math.abs(position.avgEntryPrice - entryPrice) > quantityTolerance(entryPrice)) {
                continue;
            }
            double closeQty = Math.min(position.filledQuantity, remaining);
            position.applyExit(closeQty);
            remaining -= closeQty;
            if (position.filledQuantity <= 0.0) closedKeys.add(entry.getKey());
            if (remaining <= 0.0) break;
        }
        for (String key : closedKeys) cancelLocalProtectiveOrders(key, "position closed");
    }

    private void cancelLocalProtectiveOrders(String positionKey, String reason) {
        for (LocalOrderState state : orders.values()) {
            synchronized (state) {
                if (!state.positionKey.equals(positionKey) || !state.protectiveOrder() || !state.localOpen()) continue;
                state.status = ExecutionReport.Status.CANCELLED;
                state.remainingQuantity = 0.0;
                state.lastMessage = reason == null ? "position closed" : reason;
                state.updatedAt = System.currentTimeMillis();
            }
        }
    }

    public List<ProtectiveOrderPlan> protectivePlans() {
        List<ProtectiveOrderPlan> result = new ArrayList<>();
        for (ManagedPosition position : positions.values()) {
            ProtectiveOrderPlan plan = position.protectivePlan();
            if (plan.active()) result.add(plan);
        }
        result.sort(Comparator.comparing(p -> p.symbol));
        return result;
    }

    public ProtectiveOrderPlan protectivePlan(String symbol) {
        if (symbol == null) return null;
        for (ManagedPosition position : positions.values()) {
            if (symbol.equalsIgnoreCase(position.symbol)) {
                ProtectiveOrderPlan plan = position.protectivePlan();
                if (plan.active()) return plan;
            }
        }
        return null;
    }

    public ReconciliationResult compareOpenOrders(List<OpenOrderSnapshot> exchangeOpenOrders) {
        List<OpenOrderSnapshot> exchange = exchangeOpenOrders == null ? List.of() : exchangeOpenOrders;
        Map<String, OpenOrderSnapshot> exchangeByClientId = new ConcurrentHashMap<>();
        for (OpenOrderSnapshot snapshot : exchange) {
            if (snapshot.clientOrderId != null && !snapshot.clientOrderId.isBlank()) {
                exchangeByClientId.put(snapshot.clientOrderId, snapshot);
            }
        }

        ReconciliationResult result = new ReconciliationResult();
        for (OpenOrderSnapshot snapshot : exchange) {
            LocalOrderState local = orders.get(snapshot.clientOrderId);
            if (local == null || !local.localOpen()) {
                result.orphanedOnExchange.add(snapshot);
                continue;
            }
            double localRemaining = local.remainingQuantity;
            if (Math.abs(localRemaining - snapshot.remainingQuantity) > quantityTolerance(local.order.quantity)) {
                result.quantityMismatches.add(new QuantityMismatch(local.copy(), snapshot));
            }
        }

        for (LocalOrderState local : orders.values()) {
            if (local.localOpen() && !exchangeByClientId.containsKey(local.order.clientOrderId)) {
                result.missingOnExchange.add(local.copy());
            }
        }
        return result;
    }

    private String positionKey(ExecutionOrder order, TradePlan plan) {
        String side = plan == null ? order.side.name() : (plan.longSide ? "LONG" : "SHORT");
        double entry = plan == null ? order.price : plan.entry;
        double stop = plan == null ? order.stopPrice : plan.stop;
        return order.symbol + "|" + side + "|" + roundKey(entry) + "|" + roundKey(stop);
    }

    private double quantityTolerance(double quantity) {
        return Math.max(0.00000001, Math.abs(quantity) * 0.000001);
    }

    private String roundKey(double value) {
        return Long.toString(Math.round(value * 100_000_000.0));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static class LocalOrderState {
        public final ExecutionOrder order;
        public final TradePlan plan;
        public final String positionKey;
        public String exchangeOrderId = "";
        public ExecutionReport.Status status = ExecutionReport.Status.ACCEPTED;
        public double filledQuantity;
        public double remainingQuantity;
        public String lastMessage = "";
        public long updatedAt;

        LocalOrderState(ExecutionOrder order, TradePlan plan, String positionKey) {
            this.order = order;
            this.plan = plan;
            this.positionKey = positionKey;
            this.remainingQuantity = order.quantity;
            this.updatedAt = order.createdAt;
        }

        boolean localOpen() {
            return status != ExecutionReport.Status.REJECTED
                    && status != ExecutionReport.Status.CANCELLED
                    && status != ExecutionReport.Status.FILLED
                    && remainingQuantity > 0.0;
        }

        boolean protectiveOrder() {
            return order.protectiveOrder();
        }

        LocalOrderState copy() {
            LocalOrderState copy = new LocalOrderState(order, plan, positionKey);
            copy.exchangeOrderId = exchangeOrderId;
            copy.status = status;
            copy.filledQuantity = filledQuantity;
            copy.remainingQuantity = remainingQuantity;
            copy.lastMessage = lastMessage;
            copy.updatedAt = updatedAt;
            return copy;
        }
    }

    private static class ManagedPosition {
        final String symbol;
        final boolean longSide;
        final double stopPrice;
        final double tp1Price;
        final double tp2Price;
        final double tp3Price;
        final double leverage;
        double filledQuantity;
        double avgEntryPrice;

        ManagedPosition(String symbol, TradePlan plan) {
            this.symbol = symbol == null ? "" : symbol;
            this.longSide = plan == null || plan.longSide;
            this.stopPrice = plan == null ? 0.0 : plan.stop;
            this.tp1Price = plan == null ? 0.0 : plan.tp1;
            this.tp2Price = plan == null ? 0.0 : plan.tp2;
            this.tp3Price = plan == null ? 0.0 : plan.tp3;
            this.leverage = parseSetupNumber(plan == null ? "" : plan.setupType, "lev", 1.0);
        }

        synchronized void applyFill(double quantity, double price) {
            double safeQuantity = Math.max(0.0, quantity);
            if (safeQuantity <= 0.0) return;
            double notional = avgEntryPrice * filledQuantity + price * safeQuantity;
            filledQuantity += safeQuantity;
            avgEntryPrice = notional / Math.max(filledQuantity, 0.00000001);
        }

        synchronized void applyExit(double quantity) {
            filledQuantity = Math.max(0.0, filledQuantity - Math.max(0.0, quantity));
            if (filledQuantity <= 0.0) avgEntryPrice = 0.0;
        }

        synchronized ProtectiveOrderPlan protectivePlan() {
            return new ProtectiveOrderPlan(symbol, longSide, filledQuantity, avgEntryPrice,
                    stopPrice, tp1Price, tp2Price, tp3Price);
        }

        synchronized OpenPositionSnapshot snapshot() {
            return snapshot(0.0);
        }

        synchronized OpenPositionSnapshot snapshot(double markPrice) {
            double mark = Double.isFinite(markPrice) && markPrice > 0.0 ? markPrice : 0.0;
            double pnl = mark > 0.0
                    ? (longSide ? (mark - avgEntryPrice) : (avgEntryPrice - mark)) * filledQuantity
                    : 0.0;
            double notional = filledQuantity * (mark > 0.0 ? mark : avgEntryPrice);
            return new OpenPositionSnapshot(
                    symbol,
                    longSide ? ExecutionOrder.Side.BUY : ExecutionOrder.Side.SELL,
                    filledQuantity,
                    avgEntryPrice,
                    mark,
                    notional,
                    pnl,
                    leverage,
                    System.currentTimeMillis());
        }

        synchronized double openMargin() {
            if (filledQuantity <= 0.0 || avgEntryPrice <= 0.0) return 0.0;
            return Math.abs(filledQuantity * avgEntryPrice) / Math.max(1.0, leverage);
        }
    }

    private static double parseSetupNumber(String text, String key, double fallback) {
        if (text == null || key == null || key.isBlank()) return fallback;
        String needle = key + "=";
        int start = text.indexOf(needle);
        if (start < 0) return fallback;
        start += needle.length();
        int end = start;
        while (end < text.length()) {
            char ch = text.charAt(end);
            if ((ch >= '0' && ch <= '9') || ch == '.' || ch == '-') end++;
            else break;
        }
        try {
            double value = Double.parseDouble(text.substring(start, end));
            return Double.isFinite(value) && value > 0.0 ? value : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }

    public static class QuantityMismatch {
        public final LocalOrderState local;
        public final OpenOrderSnapshot exchange;

        QuantityMismatch(LocalOrderState local, OpenOrderSnapshot exchange) {
            this.local = local;
            this.exchange = exchange;
        }
    }

    public static class ReconciliationResult {
        public final List<OpenOrderSnapshot> orphanedOnExchange = new ArrayList<>();
        public final List<LocalOrderState> missingOnExchange = new ArrayList<>();
        public final List<QuantityMismatch> quantityMismatches = new ArrayList<>();
        public final List<ExecutionReport> cancelReports = new ArrayList<>();
        public final Set<String> warnings = new LinkedHashSet<>();

        public boolean clean() {
            return orphanedOnExchange.isEmpty() && missingOnExchange.isEmpty() && quantityMismatches.isEmpty();
        }

        public String summary() {
            if (clean() && cancelReports.isEmpty()) return "reconcile clean";
            List<String> parts = new ArrayList<>();
            if (!orphanedOnExchange.isEmpty()) parts.add("orphans=" + orphanedOnExchange.size());
            if (!missingOnExchange.isEmpty()) parts.add("missing=" + missingOnExchange.size());
            if (!quantityMismatches.isEmpty()) parts.add("qtyMismatch=" + quantityMismatches.size());
            if (!cancelReports.isEmpty()) parts.add("cancelReports=" + cancelReports.size());
            if (!warnings.isEmpty()) parts.add("warnings=" + warnings.size());
            return String.join(" · ", parts);
        }
    }
}
