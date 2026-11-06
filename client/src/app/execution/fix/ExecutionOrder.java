package app.execution.fix;

public class ExecutionOrder {
    public enum Side { BUY, SELL }
    public enum Type { MARKET, LIMIT, STOP_LIMIT, STOP_MARKET }
    public enum TimeInForce { IOC, FOK, GTC, GTD }

    public final String clientOrderId;
    public final String symbol;
    public final Side side;
    public final Type type;
    public final TimeInForce timeInForce;
    public final double quantity;
    public final double price;
    public final double stopPrice;
    public final long createdAt;
    public final String strategyTag;

    public ExecutionOrder(String clientOrderId, String symbol, Side side, Type type,
                          TimeInForce timeInForce, double quantity, double price,
                          double stopPrice, String strategyTag) {
        this.clientOrderId = clientOrderId;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.timeInForce = timeInForce;
        this.quantity = quantity;
        this.price = price;
        this.stopPrice = stopPrice;
        this.createdAt = System.currentTimeMillis();
        this.strategyTag = strategyTag == null ? "" : strategyTag;
    }

    public double notional() {
        return Math.abs(quantity * price);
    }

    public boolean protectiveOrder() {
        return strategyTag != null && strategyTag.contains("protect=");
    }
}
