package app.execution.fix;

public class AccountAssetSnapshot {
    public final String asset;
    public final double walletBalance;
    public final double marginBalance;
    public final double availableBalance;
    public final double unrealizedPnl;
    public final long updatedAt;

    public AccountAssetSnapshot(String asset,
                                double walletBalance,
                                double marginBalance,
                                double availableBalance,
                                double unrealizedPnl,
                                long updatedAt) {
        this.asset = asset == null || asset.isBlank() ? "USDT" : asset.trim().toUpperCase();
        this.walletBalance = walletBalance;
        this.marginBalance = marginBalance;
        this.availableBalance = availableBalance;
        this.unrealizedPnl = unrealizedPnl;
        this.updatedAt = updatedAt <= 0L ? System.currentTimeMillis() : updatedAt;
    }

    public boolean hasValue() {
        return meaningful(walletBalance)
                || meaningful(marginBalance)
                || meaningful(availableBalance)
                || meaningful(unrealizedPnl);
    }

    public double displayBalance() {
        if (Double.isFinite(marginBalance)) return marginBalance;
        if (Double.isFinite(walletBalance)) return walletBalance;
        return availableBalance;
    }

    private static boolean meaningful(double value) {
        return Double.isFinite(value) && Math.abs(value) > 0.00000001;
    }
}
