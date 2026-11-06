package app.execution.fix;

import java.util.List;

public class AccountSnapshot {
    public final String venue;
    public final String currency;
    public final double walletBalance;
    public final double availableBalance;
    public final double equity;
    public final double unrealizedPnl;
    public final List<OpenOrderSnapshot> openOrders;
    public final List<OpenPositionSnapshot> openPositions;
    public final List<AccountAssetSnapshot> assets;
    public final long updatedAt;

    public AccountSnapshot(String venue,
                           String currency,
                           double walletBalance,
                           double availableBalance,
                           double equity,
                           double unrealizedPnl,
                           List<OpenOrderSnapshot> openOrders,
                           List<OpenPositionSnapshot> openPositions,
                           long updatedAt) {
        this(venue, currency, walletBalance, availableBalance, equity, unrealizedPnl,
                openOrders, openPositions, List.of(), updatedAt);
    }

    public AccountSnapshot(String venue,
                           String currency,
                           double walletBalance,
                           double availableBalance,
                           double equity,
                           double unrealizedPnl,
                           List<OpenOrderSnapshot> openOrders,
                           List<OpenPositionSnapshot> openPositions,
                           List<AccountAssetSnapshot> assets,
                           long updatedAt) {
        this.venue = venue == null ? "" : venue;
        this.currency = currency == null || currency.isBlank() ? "USDT" : currency;
        this.walletBalance = walletBalance;
        this.availableBalance = availableBalance;
        this.equity = equity;
        this.unrealizedPnl = unrealizedPnl;
        this.openOrders = List.copyOf(openOrders == null ? List.of() : openOrders);
        this.openPositions = List.copyOf(openPositions == null ? List.of() : openPositions);
        this.assets = List.copyOf(assets == null ? List.of() : assets);
        this.updatedAt = updatedAt <= 0L ? System.currentTimeMillis() : updatedAt;
    }

    public static AccountSnapshot ordersOnly(String venue, List<OpenOrderSnapshot> orders) {
        return new AccountSnapshot(venue, "USDT", Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                orders, List.of(), System.currentTimeMillis());
    }

    public boolean hasBalance() {
        return Double.isFinite(walletBalance) || Double.isFinite(availableBalance) || Double.isFinite(equity);
    }

    public double displayBalance() {
        if (Double.isFinite(walletBalance)) return walletBalance;
        if (Double.isFinite(equity)) return equity;
        return availableBalance;
    }

    public boolean hasAssets() {
        return !assets.isEmpty();
    }

    public int longPositions() {
        int count = 0;
        for (OpenPositionSnapshot position : openPositions) {
            if (position.longSide()) count++;
        }
        return count;
    }

    public int shortPositions() {
        return Math.max(0, openPositions.size() - longPositions());
    }

    public int buyOrders() {
        int count = 0;
        for (OpenOrderSnapshot order : openOrders) {
            if (order.side == ExecutionOrder.Side.BUY) count++;
        }
        return count;
    }

    public int sellOrders() {
        return Math.max(0, openOrders.size() - buyOrders());
    }

    public int stopOrders() {
        int count = 0;
        for (OpenOrderSnapshot order : openOrders) {
            if (order.type == ExecutionOrder.Type.STOP_LIMIT || order.type == ExecutionOrder.Type.STOP_MARKET) count++;
        }
        return count;
    }

    public int limitOrders() {
        int count = 0;
        for (OpenOrderSnapshot order : openOrders) {
            if (order.type == ExecutionOrder.Type.LIMIT) count++;
        }
        return count;
    }

    public int marketOrders() {
        int count = 0;
        for (OpenOrderSnapshot order : openOrders) {
            if (order.type == ExecutionOrder.Type.MARKET) count++;
        }
        return count;
    }
}
