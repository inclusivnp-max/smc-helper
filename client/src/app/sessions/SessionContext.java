package app.sessions;

public class SessionContext {
    public final String activeSession;
    public final String phase;
    public final boolean inKillzone;
    public final double asiaHigh;
    public final double asiaLow;
    public final double sessionHigh;
    public final double sessionLow;
    public final int asiaHighIndex;
    public final int asiaLowIndex;

    public SessionContext(String activeSession, String phase, boolean inKillzone,
                          double asiaHigh, double asiaLow, double sessionHigh, double sessionLow,
                          int asiaHighIndex, int asiaLowIndex) {
        this.activeSession = activeSession;
        this.phase = phase;
        this.inKillzone = inKillzone;
        this.asiaHigh = asiaHigh;
        this.asiaLow = asiaLow;
        this.sessionHigh = sessionHigh;
        this.sessionLow = sessionLow;
        this.asiaHighIndex = asiaHighIndex;
        this.asiaLowIndex = asiaLowIndex;
    }
}
