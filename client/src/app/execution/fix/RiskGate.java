package app.execution.fix;

import app.model.TradePlan;

import java.time.LocalDate;
import java.time.ZoneId;

public class RiskGate {
    public final double maxOrderNotional;
    public final double maxRiskNotional;
    public final int minSmcScore;
    public final boolean requireConfirmedEntry;
    public final double maxDailyLossNotional;
    public final double maxSingleOrderQuantity;
    public final double maxEntryDeviationPct;
    public final double maxStopDistancePct;
    public final double maxDailyLossPct;
    public final int maxTradesPerDay;
    public final int maxConsecutiveLosses;
    public final int maxOpenPositions;
    public final int duplicateOrderLockSeconds;

    private LocalDate tradingDay = LocalDate.now(ZoneId.systemDefault());
    private double dailyRealizedPnl;
    private double dayStartEquity;
    private int tradesToday;
    private int consecutiveLosses;
    private boolean killSwitchTripped;
    private String killSwitchReason = "";

    public RiskGate(double maxOrderNotional, double maxRiskNotional,
                    int minSmcScore, boolean requireConfirmedEntry) {
        this(maxOrderNotional, maxRiskNotional, minSmcScore, requireConfirmedEntry,
                350.0, 1_000.0, 1.25, 3.50);
    }

    public RiskGate(double maxOrderNotional, double maxRiskNotional,
                    int minSmcScore, boolean requireConfirmedEntry,
                    double maxDailyLossNotional,
                    double maxSingleOrderQuantity,
                    double maxEntryDeviationPct,
                    double maxStopDistancePct) {
        this(maxOrderNotional, maxRiskNotional, minSmcScore, requireConfirmedEntry,
                maxDailyLossNotional, maxSingleOrderQuantity, maxEntryDeviationPct, maxStopDistancePct,
                0.0, 0, 0, 0, 45);
    }

    public RiskGate(double maxOrderNotional, double maxRiskNotional,
                    int minSmcScore, boolean requireConfirmedEntry,
                    double maxDailyLossNotional,
                    double maxSingleOrderQuantity,
                    double maxEntryDeviationPct,
                    double maxStopDistancePct,
                    double maxDailyLossPct,
                    int maxTradesPerDay,
                    int maxConsecutiveLosses,
                    int maxOpenPositions,
                    int duplicateOrderLockSeconds) {
        this.maxOrderNotional = Math.max(0.0, maxOrderNotional);
        this.maxRiskNotional = Math.max(0.0, maxRiskNotional);
        this.minSmcScore = Math.max(0, Math.min(100, minSmcScore));
        this.requireConfirmedEntry = requireConfirmedEntry;
        this.maxDailyLossNotional = Math.max(0.0, maxDailyLossNotional);
        this.maxSingleOrderQuantity = Math.max(0.0, maxSingleOrderQuantity);
        this.maxEntryDeviationPct = Math.max(0.0, maxEntryDeviationPct);
        this.maxStopDistancePct = Math.max(0.0, maxStopDistancePct);
        this.maxDailyLossPct = Math.max(0.0, maxDailyLossPct);
        this.maxTradesPerDay = Math.max(0, maxTradesPerDay);
        this.maxConsecutiveLosses = Math.max(0, maxConsecutiveLosses);
        this.maxOpenPositions = Math.max(0, maxOpenPositions);
        this.duplicateOrderLockSeconds = Math.max(0, duplicateOrderLockSeconds);
    }

    public static RiskGate strictPaperDefault() {
        return RiskSettings.load().toRiskGate();
    }

    public Validation validate(ExecutionOrder order, TradePlan plan) {
        RiskStatus status = status();
        if (status.killSwitchTripped) return Validation.reject("KILL SWITCH: " + status.reason);
        if (status.maxDailyLossReached) return Validation.reject("Превышен дневной лимит убытка");
        if (status.maxTradesReached) return Validation.reject("Превышен лимит сделок за день");
        if (status.maxConsecutiveLossesReached) return Validation.reject("Превышен лимит убыточной серии");
        if (order == null) return Validation.reject("Пустой order");
        if (order.symbol == null || order.symbol.isBlank()) return Validation.reject("Нет symbol");
        if (order.quantity <= 0.0) return Validation.reject("Quantity должна быть больше 0");
        if (maxSingleOrderQuantity > 0.0 && order.quantity > maxSingleOrderQuantity) {
            return Validation.reject("Fat finger: quantity выше лимита");
        }
        if (order.type != ExecutionOrder.Type.MARKET
                && order.type != ExecutionOrder.Type.STOP_MARKET
                && order.price <= 0.0) return Validation.reject("Limit price должна быть больше 0");
        if (order.notional() > maxOrderNotional) return Validation.reject("Превышен max order notional");
        if (plan == null) return Validation.reject("Нет TradePlan для risk validation");
        if (plan.confidence < minSmcScore) return Validation.reject("SMC ниже risk gate");
        if (requireConfirmedEntry && !plan.entryConfirmed) return Validation.reject("Entry еще не подтвержден");
        if (plan.entry <= 0.0) return Validation.reject("TradePlan entry должен быть больше 0");
        if (order.type != ExecutionOrder.Type.MARKET
                && order.type != ExecutionOrder.Type.STOP_MARKET
                && maxEntryDeviationPct > 0.0) {
            double deviationPct = Math.abs(order.price - plan.entry) / plan.entry * 100.0;
            if (deviationPct > maxEntryDeviationPct) {
                return Validation.reject("Fat finger: limit price далеко от TradePlan entry");
            }
        }
        double riskPerUnit = Math.abs(plan.entry - plan.stop);
        double stopDistancePct = riskPerUnit / plan.entry * 100.0;
        if (maxStopDistancePct > 0.0 && stopDistancePct > maxStopDistancePct) {
            return Validation.reject("Fat finger: stop distance выше лимита");
        }
        if (riskPerUnit * order.quantity > maxRiskNotional) return Validation.reject("Превышен риск по стопу");
        boolean sideMatches = order.side == (plan.longSide ? ExecutionOrder.Side.BUY : ExecutionOrder.Side.SELL);
        if (!sideMatches) return Validation.reject("Сторона order не совпадает с TradePlan");
        return Validation.accept();
    }

    public synchronized void recordRealizedPnl(double pnl) {
        rollDayIfNeeded();
        dailyRealizedPnl += pnl;
        if (pnl < -0.00000001) consecutiveLosses++;
        else if (pnl > 0.00000001) consecutiveLosses = 0;
        if (maxDailyLossNotional > 0.0 && dailyRealizedPnl <= -maxDailyLossNotional) {
            tripKillSwitchLocked("daily loss " + formatMoney(dailyRealizedPnl)
                    + " <= -" + formatMoney(maxDailyLossNotional));
        }
        if (maxDailyLossPct > 0.0 && dayStartEquity > 0.0
                && dailyRealizedPnl <= -(dayStartEquity * maxDailyLossPct / 100.0)) {
            tripKillSwitchLocked("daily loss " + formatMoney(dailyRealizedPnl)
                    + " <= -" + maxDailyLossPct + "% of start equity");
        }
        if (maxConsecutiveLosses > 0 && consecutiveLosses >= maxConsecutiveLosses) {
            tripKillSwitchLocked("consecutive losses " + consecutiveLosses
                    + " >= " + maxConsecutiveLosses);
        }
    }

    public synchronized void recordRealizedLoss(double loss) {
        recordRealizedPnl(-Math.abs(loss));
    }

    public synchronized void tripKillSwitch(String reason) {
        rollDayIfNeeded();
        tripKillSwitchLocked(reason == null || reason.isBlank() ? "manual" : reason);
    }

    public synchronized void resetKillSwitch() {
        rollDayIfNeeded();
        killSwitchTripped = false;
        killSwitchReason = "";
    }

    public synchronized void restoreDailyStats(LocalDate day,
                                               double realizedPnl,
                                               int tradeCount,
                                               int lossStreak) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (day == null || !today.equals(day)) return;
        tradingDay = today;
        dailyRealizedPnl = realizedPnl;
        tradesToday = Math.max(0, tradeCount);
        consecutiveLosses = Math.max(0, lossStreak);
        status();
    }

    public synchronized void setDayStartEquity(double equity) {
        rollDayIfNeeded();
        if (Double.isFinite(equity) && equity > 0.0) {
            dayStartEquity = equity;
            status();
        }
    }

    public synchronized void recordTradeAccepted() {
        rollDayIfNeeded();
        tradesToday++;
        if (maxTradesPerDay > 0 && tradesToday >= maxTradesPerDay) {
            tripKillSwitchLocked("trades today " + tradesToday + " >= " + maxTradesPerDay);
        }
    }

    public synchronized RiskStatus status() {
        rollDayIfNeeded();
        boolean maxDailyLossReached = maxDailyLossNotional > 0.0
                && dailyRealizedPnl <= -maxDailyLossNotional;
        boolean maxDailyLossPctReached = maxDailyLossPct > 0.0 && dayStartEquity > 0.0
                && dailyRealizedPnl <= -(dayStartEquity * maxDailyLossPct / 100.0);
        boolean maxTradesReached = maxTradesPerDay > 0 && tradesToday >= maxTradesPerDay;
        boolean maxConsecutiveLossesReached = maxConsecutiveLosses > 0
                && consecutiveLosses >= maxConsecutiveLosses;
        if (maxDailyLossReached && !killSwitchTripped) {
            tripKillSwitchLocked("daily loss " + formatMoney(dailyRealizedPnl)
                    + " <= -" + formatMoney(maxDailyLossNotional));
        }
        if (maxDailyLossPctReached && !killSwitchTripped) {
            tripKillSwitchLocked("daily loss " + formatMoney(dailyRealizedPnl)
                    + " <= -" + maxDailyLossPct + "% of start equity");
        }
        if (maxTradesReached && !killSwitchTripped) {
            tripKillSwitchLocked("trades today " + tradesToday + " >= " + maxTradesPerDay);
        }
        if (maxConsecutiveLossesReached && !killSwitchTripped) {
            tripKillSwitchLocked("consecutive losses " + consecutiveLosses
                    + " >= " + maxConsecutiveLosses);
        }
        return new RiskStatus(tradingDay, dailyRealizedPnl, maxDailyLossNotional,
                maxDailyLossPct, dayStartEquity, tradesToday, maxTradesPerDay,
                consecutiveLosses, maxConsecutiveLosses, killSwitchTripped, killSwitchReason,
                maxDailyLossReached || maxDailyLossPctReached, maxTradesReached,
                maxConsecutiveLossesReached);
    }

    private void rollDayIfNeeded() {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (!today.equals(tradingDay)) {
            tradingDay = today;
            dailyRealizedPnl = 0.0;
            tradesToday = 0;
            consecutiveLosses = 0;
            dayStartEquity = 0.0;
            killSwitchTripped = false;
            killSwitchReason = "";
        }
    }

    private void tripKillSwitchLocked(String reason) {
        killSwitchTripped = true;
        killSwitchReason = reason == null || reason.isBlank() ? "risk limit" : reason;
    }

    private String formatMoney(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    public static class RiskStatus {
        public final LocalDate tradingDay;
        public final double dailyRealizedPnl;
        public final double maxDailyLossNotional;
        public final double maxDailyLossPct;
        public final double dayStartEquity;
        public final int tradesToday;
        public final int maxTradesPerDay;
        public final int consecutiveLosses;
        public final int maxConsecutiveLosses;
        public final boolean killSwitchTripped;
        public final String reason;
        public final boolean maxDailyLossReached;
        public final boolean maxTradesReached;
        public final boolean maxConsecutiveLossesReached;

        RiskStatus(LocalDate tradingDay,
                   double dailyRealizedPnl,
                   double maxDailyLossNotional,
                   double maxDailyLossPct,
                   double dayStartEquity,
                   int tradesToday,
                   int maxTradesPerDay,
                   int consecutiveLosses,
                   int maxConsecutiveLosses,
                   boolean killSwitchTripped,
                   String reason,
                   boolean maxDailyLossReached,
                   boolean maxTradesReached,
                   boolean maxConsecutiveLossesReached) {
            this.tradingDay = tradingDay;
            this.dailyRealizedPnl = dailyRealizedPnl;
            this.maxDailyLossNotional = maxDailyLossNotional;
            this.maxDailyLossPct = maxDailyLossPct;
            this.dayStartEquity = dayStartEquity;
            this.tradesToday = tradesToday;
            this.maxTradesPerDay = maxTradesPerDay;
            this.consecutiveLosses = consecutiveLosses;
            this.maxConsecutiveLosses = maxConsecutiveLosses;
            this.killSwitchTripped = killSwitchTripped;
            this.reason = reason == null ? "" : reason;
            this.maxDailyLossReached = maxDailyLossReached;
            this.maxTradesReached = maxTradesReached;
            this.maxConsecutiveLossesReached = maxConsecutiveLossesReached;
        }

        public String summary() {
            String state = killSwitchTripped ? "KILL SWITCH: " + reason : "ACTIVE";
            return state + " · dayPnL=" + String.format(java.util.Locale.US, "%.2f", dailyRealizedPnl)
                    + " · maxLoss=" + String.format(java.util.Locale.US, "%.2f", maxDailyLossNotional)
                    + "/" + String.format(java.util.Locale.US, "%.2f", maxDailyLossPct) + "%"
                    + " · trades=" + tradesToday + "/" + maxTradesPerDay
                    + " · lossStreak=" + consecutiveLosses + "/" + maxConsecutiveLosses;
        }
    }

    public static class Validation {
        public final boolean accepted;
        public final String reason;

        private Validation(boolean accepted, String reason) {
            this.accepted = accepted;
            this.reason = reason;
        }

        static Validation accept() {
            return new Validation(true, "OK");
        }

        static Validation reject(String reason) {
            return new Validation(false, reason);
        }
    }
}
