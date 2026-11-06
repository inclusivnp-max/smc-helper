package app.execution.fix;

import java.util.List;

public class UnavailableExchangeGateway implements ExecutionGateway {
    private final String venue;
    private final String reason;

    public UnavailableExchangeGateway(String venue, String reason) {
        this.venue = venue == null || venue.isBlank() ? "UNAVAILABLE" : venue;
        this.reason = reason == null || reason.isBlank() ? "gateway is not implemented" : reason;
    }

    @Override
    public ExecutionReport send(ExecutionOrder order) {
        return new ExecutionReport(order.clientOrderId, "", ExecutionReport.Status.REJECTED,
                0.0, 0.0, reason);
    }

    @Override
    public ExecutionReport cancel(String clientOrderId) {
        return new ExecutionReport(clientOrderId, "", ExecutionReport.Status.REJECTED,
                0.0, 0.0, reason);
    }

    @Override
    public List<OpenOrderSnapshot> openOrders() {
        return List.of();
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public String venue() {
        return venue;
    }
}
