package app.fundamental;

public class EventRiskContext {
    public static final EventRiskContext NONE = new EventRiskContext(false, 0, 0,
            false, false, false, "", "No event risk");

    public final boolean available;
    public final int score;
    public final int minutesToEvent;
    public final boolean highImpactWindow;
    public final boolean marketShock;
    public final boolean fundingCrowded;
    public final String label;
    public final String message;

    public EventRiskContext(boolean available,
                            int score,
                            int minutesToEvent,
                            boolean highImpactWindow,
                            boolean marketShock,
                            boolean fundingCrowded,
                            String label,
                            String message) {
        this.available = available;
        this.score = Math.max(0, Math.min(100, score));
        this.minutesToEvent = minutesToEvent;
        this.highImpactWindow = highImpactWindow;
        this.marketShock = marketShock;
        this.fundingCrowded = fundingCrowded;
        this.label = label == null ? "" : label;
        this.message = message == null ? "" : message;
    }

    public boolean blocksNewRisk() {
        return highImpactWindow || score >= 75;
    }
}
