package app.ui;

import app.model.TradePlan;

public final class ForecastPlanState {
    private boolean pendingRadar;
    private String pendingSymbol = "";
    private boolean sticky;
    private String stickySymbol = "";
    private TradePlan plan;

    public void armRadar(String symbol, TradePlan plan) {
        pendingRadar = true;
        pendingSymbol = clean(symbol);
        stick(pendingSymbol, plan);
    }

    public void stick(String symbol, TradePlan plan) {
        sticky = true;
        stickySymbol = clean(symbol);
        this.plan = plan;
    }

    public void stickIfEmpty(String symbol, TradePlan fallbackPlan) {
        sticky = true;
        stickySymbol = clean(symbol);
        if (plan == null && fallbackPlan != null) {
            plan = fallbackPlan;
        }
    }

    public boolean consumePendingFor(String symbol) {
        if (!pendingMatches(symbol)) return false;
        pendingRadar = false;
        pendingSymbol = "";
        return true;
    }

    public boolean pendingMatches(String symbol) {
        String cleanSymbol = clean(symbol);
        return pendingRadar && (pendingSymbol.isBlank() || pendingSymbol.equalsIgnoreCase(cleanSymbol));
    }

    public boolean stickyMatches(String symbol) {
        String cleanSymbol = clean(symbol);
        return sticky && (stickySymbol.isBlank() || stickySymbol.equalsIgnoreCase(cleanSymbol));
    }

    public void clearIfDifferentSymbol(String symbol) {
        String cleanSymbol = clean(symbol);
        if (!pendingRadar && sticky && !stickySymbol.isBlank() && !stickySymbol.equalsIgnoreCase(cleanSymbol)) {
            clear();
        }
    }

    public void clear() {
        pendingRadar = false;
        pendingSymbol = "";
        clearSticky();
    }

    public void clearSticky() {
        sticky = false;
        stickySymbol = "";
        plan = null;
    }

    public boolean sticky() {
        return sticky;
    }

    public boolean hasPlan() {
        return plan != null;
    }

    public TradePlan plan() {
        return plan;
    }

    public String stickySymbol() {
        return stickySymbol;
    }

    private static String clean(String symbol) {
        return symbol == null ? "" : symbol.trim();
    }
}
