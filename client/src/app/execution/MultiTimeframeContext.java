package app.execution;

import app.common.Direction;

public class MultiTimeframeContext {
    public final Direction htfBias;
    public final Direction ltfBias;
    public final boolean aligned;
    public final boolean pullbackAgainstHtf;
    public final String narrative;
    public final int score;

    public MultiTimeframeContext(Direction htfBias, Direction ltfBias, boolean aligned,
                                 boolean pullbackAgainstHtf, String narrative, int score) {
        this.htfBias = htfBias;
        this.ltfBias = ltfBias;
        this.aligned = aligned;
        this.pullbackAgainstHtf = pullbackAgainstHtf;
        this.narrative = narrative;
        this.score = score;
    }
}
