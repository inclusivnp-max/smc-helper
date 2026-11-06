package app.orderflow;

public class FootprintLevel {
    public final double price;
    public final double buyVolume;
    public final double sellVolume;
    public final double delta;
    public final double totalVolume;

    public FootprintLevel(double price, double buyVolume, double sellVolume) {
        this.price = price;
        this.buyVolume = Math.max(0.0, buyVolume);
        this.sellVolume = Math.max(0.0, sellVolume);
        this.delta = this.buyVolume - this.sellVolume;
        this.totalVolume = this.buyVolume + this.sellVolume;
    }

    public boolean buyImbalance() {
        return buyVolume >= sellVolume * 2.4 && totalVolume > 0.0;
    }

    public boolean sellImbalance() {
        return sellVolume >= buyVolume * 2.4 && totalVolume > 0.0;
    }
}
