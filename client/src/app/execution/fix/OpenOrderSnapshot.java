package app.execution.fix;

public class OpenOrderSnapshot {
    public final String clientOrderId;
    public final String exchangeOrderId;
    public final String symbol;
    public final ExecutionOrder.Side side;
    public final ExecutionOrder.Type type;
    public final ExecutionOrder.TimeInForce timeInForce;
    public final double originalQuantity;
    public final double filledQuantity;
    public final double remainingQuantity;
    public final double price;
    public final double stopPrice;
    public final long updatedAt;

    public OpenOrderSnapshot(String clientOrderId,
                             String exchangeOrderId,
                             String symbol,
                             ExecutionOrder.Side side,
                             ExecutionOrder.Type type,
                             ExecutionOrder.TimeInForce timeInForce,
                             double originalQuantity,
                             double filledQuantity,
                             double remainingQuantity,
                             double price,
                             double stopPrice,
                             long updatedAt) {
        this.clientOrderId = clientOrderId == null ? "" : clientOrderId;
        this.exchangeOrderId = exchangeOrderId == null ? "" : exchangeOrderId;
        this.symbol = symbol == null ? "" : symbol;
        this.side = side;
        this.type = type;
        this.timeInForce = timeInForce;
        this.originalQuantity = Math.max(0.0, originalQuantity);
        this.filledQuantity = Math.max(0.0, filledQuantity);
        this.remainingQuantity = Math.max(0.0, remainingQuantity);
        this.price = price;
        this.stopPrice = stopPrice;
        this.updatedAt = updatedAt <= 0L ? System.currentTimeMillis() : updatedAt;
    }

    public static OpenOrderSnapshot from(ExecutionOrder order,
                                         String exchangeOrderId,
                                         double filledQuantity) {
        double filled = Math.max(0.0, Math.min(order.quantity, filledQuantity));
        return new OpenOrderSnapshot(
                order.clientOrderId,
                exchangeOrderId,
                order.symbol,
                order.side,
                order.type,
                order.timeInForce,
                order.quantity,
                filled,
                Math.max(0.0, order.quantity - filled),
                order.price,
                order.stopPrice,
                System.currentTimeMillis());
    }

    public boolean open() {
        return remainingQuantity > 0.0;
    }
}
