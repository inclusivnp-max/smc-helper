package app.volume;

import app.common.Direction;

public class VolumeSignal {
    public final int index;
    public final double volumeRatio;
    public final double deltaProxy;
    public final Direction aggressiveSide;
    public final int score;

    public VolumeSignal(int index, double volumeRatio, double deltaProxy,
                        Direction aggressiveSide, int score) {
        this.index = index;
        this.volumeRatio = volumeRatio;
        this.deltaProxy = deltaProxy;
        this.aggressiveSide = aggressiveSide;
        this.score = score;
    }

    public boolean spike() {
        return volumeRatio >= 1.50;
    }
}
