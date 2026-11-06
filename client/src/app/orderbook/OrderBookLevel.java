package app.orderbook;

public class OrderBookLevel {
    public final double price;
    public final double quantity;

    public OrderBookLevel(double price, double quantity) {
        this.price = price;
        this.quantity = quantity;
    }

    public double notional() {
        return price * quantity;
    }
}
