package app.backtest;

public class Tick {
    public final long aggregateId;
    public final long time;
    public final double price;
    public final double quantity;
    public final boolean buyerMaker;

    public Tick(long aggregateId, long time, double price, double quantity, boolean buyerMaker) {
        this.aggregateId = aggregateId;
        this.time = time;
        this.price = price;
        this.quantity = quantity;
        this.buyerMaker = buyerMaker;
    }
}
