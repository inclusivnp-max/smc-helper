package app.execution.fix;

public class ExecutionReport {
    public enum Status {
        ACCEPTED,
        REJECTED,
        PARTIALLY_FILLED,
        FILLED,
        CANCELLED
    }

    public final String clientOrderId;
    public final String exchangeOrderId;
    public final Status status;
    public final double filledQuantity;
    public final double remainingQuantity;
    public final double avgPrice;
    public final String message;
    public final long time;

    public ExecutionReport(String clientOrderId, String exchangeOrderId, Status status,
                           double filledQuantity, double avgPrice, String message) {
        this(clientOrderId, exchangeOrderId, status, filledQuantity, Double.NaN, avgPrice, message);
    }

    public ExecutionReport(String clientOrderId, String exchangeOrderId, Status status,
                           double filledQuantity, double remainingQuantity,
                           double avgPrice, String message) {
        this.clientOrderId = clientOrderId;
        this.exchangeOrderId = exchangeOrderId;
        this.status = status;
        this.filledQuantity = filledQuantity;
        this.remainingQuantity = remainingQuantity;
        this.avgPrice = avgPrice;
        this.message = message == null ? "" : message;
        this.time = System.currentTimeMillis();
    }
}
