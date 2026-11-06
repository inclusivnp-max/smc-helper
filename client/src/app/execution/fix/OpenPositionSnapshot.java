package app.execution.fix;

public class OpenPositionSnapshot {
    public final String symbol;
    public final ExecutionOrder.Side side;
    public final double quantity;
    public final double entryPrice;
    public final double markPrice;
    public final double notional;
    public final double unrealizedPnl;
    public final double leverage;
    public final long updatedAt;

    public OpenPositionSnapshot(String symbol,
                                ExecutionOrder.Side side,
                                double quantity,
                                double entryPrice,
                                double markPrice,
                                double notional,
                                double unrealizedPnl,
                                double leverage,
                                long updatedAt) {
        this.symbol = symbol == null ? "" : symbol;
        this.side = side == null ? ExecutionOrder.Side.BUY : side;
        this.quantity = Math.max(0.0, Math.abs(quantity));
        this.entryPrice = entryPrice;
        this.markPrice = markPrice;
        this.notional = Math.abs(notional);
        this.unrealizedPnl = unrealizedPnl;
        this.leverage = leverage;
        this.updatedAt = updatedAt <= 0L ? System.currentTimeMillis() : updatedAt;
    }

    public boolean longSide() {
        return side == ExecutionOrder.Side.BUY;
    }

    public String sideLabel() {
        return longSide() ? "LONG" : "SHORT";
    }
}
