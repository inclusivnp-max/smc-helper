package app.scoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SmcScore {
    public final int score;
    public final List<String> factors;
    public final List<String> warnings;

    public SmcScore(int score, List<String> factors, List<String> warnings) {
        this.score = Math.max(0, Math.min(100, score));
        this.factors = Collections.unmodifiableList(new ArrayList<>(factors));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }
}
