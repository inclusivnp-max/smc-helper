package app.execution.fix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ProtectiveOrderPlan {
    private static final double TP1_SHARE = 0.40;
    private static final double TP2_SHARE = 0.40;

    public final String symbol;
    public final boolean longSide;
    public final double filledQuantity;
    public final double avgEntryPrice;
    public final double stopPrice;
    public final double tp1Price;
    public final double tp2Price;
    public final double tp3Price;
    public final double stopQuantity;
    public final double tp1Quantity;
    public final double tp2Quantity;
    public final double tp3Quantity;
    public final long updatedAt;

    public ProtectiveOrderPlan(String symbol,
                               boolean longSide,
                               double filledQuantity,
                               double avgEntryPrice,
                               double stopPrice,
                               double tp1Price,
                               double tp2Price,
                               double tp3Price) {
        this.symbol = symbol == null ? "" : symbol;
        this.longSide = longSide;
        this.filledQuantity = Math.max(0.0, filledQuantity);
        this.avgEntryPrice = avgEntryPrice;
        this.stopPrice = stopPrice;
        this.tp1Price = tp1Price;
        this.tp2Price = tp2Price;
        this.tp3Price = tp3Price;
        this.stopQuantity = this.filledQuantity;
        this.tp1Quantity = roundQty(this.filledQuantity * TP1_SHARE);
        this.tp2Quantity = roundQty(this.filledQuantity * TP2_SHARE);
        this.tp3Quantity = roundQty(Math.max(0.0, this.filledQuantity - this.tp1Quantity - this.tp2Quantity));
        this.updatedAt = System.currentTimeMillis();
    }

    public boolean active() {
        return filledQuantity > 0.0 && stopPrice > 0.0;
    }

    public List<ExecutionOrder> buildOrders(String groupId) {
        if (!active()) return List.of();
        String prefix = (groupId == null || groupId.isBlank()) ? "SMC-PROTECT" : groupId;
        ExecutionOrder.Side exitSide = longSide ? ExecutionOrder.Side.SELL : ExecutionOrder.Side.BUY;
        List<ExecutionOrder> orders = new ArrayList<>();
        orders.add(new ExecutionOrder(prefix + "-SL", symbol, exitSide,
                ExecutionOrder.Type.STOP_MARKET, ExecutionOrder.TimeInForce.GTC,
                stopQuantity, stopPrice, stopPrice, tag("SL", stopQuantity)));
        if (tp1Quantity > 0.0) {
            orders.add(new ExecutionOrder(prefix + "-TP1", symbol, exitSide,
                    ExecutionOrder.Type.LIMIT, ExecutionOrder.TimeInForce.GTC,
                    tp1Quantity, tp1Price, 0.0, tag("TP1", tp1Quantity)));
        }
        if (tp2Quantity > 0.0) {
            orders.add(new ExecutionOrder(prefix + "-TP2", symbol, exitSide,
                    ExecutionOrder.Type.LIMIT, ExecutionOrder.TimeInForce.GTC,
                    tp2Quantity, tp2Price, 0.0, tag("TP2", tp2Quantity)));
        }
        if (tp3Quantity > 0.0) {
            orders.add(new ExecutionOrder(prefix + "-TP3", symbol, exitSide,
                    ExecutionOrder.Type.LIMIT, ExecutionOrder.TimeInForce.GTC,
                    tp3Quantity, tp3Price, 0.0, tag("TP3", tp3Quantity)));
        }
        return orders;
    }

    private String tag(String leg, double quantity) {
        return String.format(Locale.US, "protect=%s filledQty=%.8f avgEntry=%.8f", leg, quantity, avgEntryPrice);
    }

    private static double roundQty(double value) {
        return Math.round(Math.max(0.0, value) * 100_000_000.0) / 100_000_000.0;
    }
}
