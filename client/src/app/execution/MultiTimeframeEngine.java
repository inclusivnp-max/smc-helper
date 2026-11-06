package app.execution;

import app.common.Direction;
import app.common.ScoreUtils;
import app.structure.MarketStructure;

public class MultiTimeframeEngine {

    // Base score when structure is present but not aligned
    private static final int BASE_SCORE = 35;
    // HTF strength contributes at 1/3 weight (momentum is a lagging signal)
    private static final int HTF_STRENGTH_DIVISOR = 3;
    // LTF strength contributes at 1/5 weight (noise filter)
    private static final int LTF_STRENGTH_DIVISOR = 5;
    // Bonus when both timeframes confirm the same bias
    private static final int ALIGNMENT_BONUS = 18;
    // Smaller bonus for a clean pullback: valid setup but riskier entry
    private static final int PULLBACK_BONUS = 8;

    public MultiTimeframeContext analyze(MarketStructure htf, MarketStructure ltf) {
        Direction htfBias = htf.bias;
        Direction ltfBias = ltf.bias;
        boolean aligned  = htfBias == ltfBias;
        boolean pullback = htfBias != Direction.NEUTRAL && ltfBias == htfBias.opposite();

        int score = BASE_SCORE
                + htf.strength / HTF_STRENGTH_DIVISOR
                + ltf.strength / LTF_STRENGTH_DIVISOR;
        if (aligned)  score += ALIGNMENT_BONUS;
        if (pullback) score += PULLBACK_BONUS;
        score = ScoreUtils.clamp(score, 0, 100);

        String narrative;
        if (aligned) {
            narrative = "HTF " + htfBias.label() + ", LTF confirms continuation.";
        } else if (pullback) {
            narrative = "HTF " + htfBias.label() + ", LTF is pulling back; wait for entry CHoCH.";
        } else {
            narrative = "Mixed structure; require sweep and displacement confirmation.";
        }

        return new MultiTimeframeContext(htfBias, ltfBias, aligned, pullback, narrative, score);
    }
}
