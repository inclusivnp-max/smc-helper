package app.execution.algo;

public class ExecutionSlice {
    public final int index;
    public final long scheduledAt;
    public final double quantity;
    public final double limitPrice;
    public final String tag;

    public ExecutionSlice(int index, long scheduledAt, double quantity, double limitPrice, String tag) {
        this.index = index;
        this.scheduledAt = scheduledAt;
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.tag = tag == null ? "" : tag;
    }
}
