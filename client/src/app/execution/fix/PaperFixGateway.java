package app.execution.fix;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PaperFixGateway implements ExecutionGateway {
    private final Map<String, PaperOrderState> openOrders = new ConcurrentHashMap<>();
    private final String venue;

    public PaperFixGateway() {
        this("PAPER-FIX");
    }

    public PaperFixGateway(String venue) {
        this.venue = venue;
    }

    @Override
    public ExecutionReport send(ExecutionOrder order) {
        String exchangeId = venue + "-" + System.nanoTime();
        openOrders.put(order.clientOrderId, new PaperOrderState(order, exchangeId));
        return new ExecutionReport(order.clientOrderId, exchangeId,
                ExecutionReport.Status.ACCEPTED, 0.0, order.quantity, 0.0, "Paper FIX accepted");
    }

    @Override
    public ExecutionReport cancel(String clientOrderId) {
        PaperOrderState removed = openOrders.remove(clientOrderId);
        if (removed == null) {
            return new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "Order not found");
        }
        return new ExecutionReport(clientOrderId, removed.exchangeOrderId, ExecutionReport.Status.CANCELLED,
                removed.filledQuantity, removed.remainingQuantity(), removed.avgPrice, "Paper FIX cancelled");
    }

    @Override
    public List<OpenOrderSnapshot> openOrders() {
        List<OpenOrderSnapshot> result = new ArrayList<>();
        for (PaperOrderState state : openOrders.values()) {
            if (state.remainingQuantity() > 0.0) {
                result.add(OpenOrderSnapshot.from(state.order, state.exchangeOrderId, state.filledQuantity));
            }
        }
        return result;
    }

    @Override
    public AccountSnapshot accountSnapshot() {
        return AccountSnapshot.ordersOnly(venue, openOrders());
    }

    public ExecutionReport simulateFill(String clientOrderId, double quantity, double avgPrice) {
        PaperOrderState state = openOrders.get(clientOrderId);
        if (state == null) {
            return new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                    0.0, 0.0, "Order not found");
        }
        double fill = Math.max(0.0, Math.min(quantity, state.remainingQuantity()));
        if (fill <= 0.0) {
            return new ExecutionReport(clientOrderId, state.exchangeOrderId, ExecutionReport.Status.ACCEPTED,
                    state.filledQuantity, state.remainingQuantity(), state.avgPrice, "No fill");
        }
        double price = avgPrice > 0.0 ? avgPrice : state.order.price;
        double notional = state.avgPrice * state.filledQuantity + price * fill;
        state.filledQuantity += fill;
        state.avgPrice = notional / Math.max(state.filledQuantity, 0.00000001);
        ExecutionReport.Status status = state.remainingQuantity() <= 0.0
                ? ExecutionReport.Status.FILLED
                : ExecutionReport.Status.PARTIALLY_FILLED;
        if (status == ExecutionReport.Status.FILLED) {
            openOrders.remove(clientOrderId);
        }
        return new ExecutionReport(clientOrderId, state.exchangeOrderId, status,
                state.filledQuantity, state.remainingQuantity(), state.avgPrice,
                status == ExecutionReport.Status.FILLED ? "Paper fill complete" : "Paper partial fill");
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public String venue() {
        return venue;
    }

    private static class PaperOrderState {
        final ExecutionOrder order;
        final String exchangeOrderId;
        double filledQuantity;
        double avgPrice;

        PaperOrderState(ExecutionOrder order, String exchangeOrderId) {
            this.order = order;
            this.exchangeOrderId = exchangeOrderId;
        }

        double remainingQuantity() {
            return Math.max(0.0, order.quantity - filledQuantity);
        }
    }
}
