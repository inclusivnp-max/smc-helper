package app.displacement;

import app.common.Direction;

public class DisplacementEvent {
    public final int index;
    public final Direction direction;
    public final int score;
    public final String quality;
    public final double bodyPercent;
    public final double bodyExpansion;
    public final double atrExpansion;
    public final double volumeRatio;
    public final double imbalanceSize;
    public final int consecutiveImpulses;

    public DisplacementEvent(int index, Direction direction, int score, String quality,
                             double bodyPercent, double bodyExpansion, double atrExpansion,
                             double volumeRatio, double imbalanceSize, int consecutiveImpulses) {
        this.index = index;
        this.direction = direction;
        this.score = score;
        this.quality = quality;
        this.bodyPercent = bodyPercent;
        this.bodyExpansion = bodyExpansion;
        this.atrExpansion = atrExpansion;
        this.volumeRatio = volumeRatio;
        this.imbalanceSize = imbalanceSize;
        this.consecutiveImpulses = consecutiveImpulses;
    }

    public boolean strong() {
        return score >= 72;
    }
}
