package app.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ChartInsight {
    public enum Tone {
        BULLISH,
        BEARISH,
        NEUTRAL,
        WARNING,
        DANGER
    }

    public enum LevelType {
        CURRENT,
        ENTRY,
        STOP,
        TARGET,
        EQUILIBRIUM,
        ZONE,
        STRUCTURE,
        ALERT
    }

    public final String symbol;
    public final String htfInterval;
    public final String ltfInterval;
    public final String marketLabel;
    public final String heading;
    public final String action;
    public final String summary;
    public final int score;
    public final Tone tone;
    public final List<Metric> metrics;
    public final List<String> confirmations;
    public final List<String> risks;
    public final List<String> nextSteps;
    public final List<LevelMarker> levels;

    public ChartInsight(String symbol,
                        String htfInterval,
                        String ltfInterval,
                        String marketLabel,
                        String heading,
                        String action,
                        String summary,
                        int score,
                        Tone tone,
                        List<Metric> metrics,
                        List<String> confirmations,
                        List<String> risks,
                        List<String> nextSteps,
                        List<LevelMarker> levels) {
        this.symbol = clean(symbol);
        this.htfInterval = clean(htfInterval);
        this.ltfInterval = clean(ltfInterval);
        this.marketLabel = clean(marketLabel);
        this.heading = clean(heading);
        this.action = clean(action);
        this.summary = clean(summary);
        this.score = Math.max(0, Math.min(100, score));
        this.tone = tone == null ? Tone.NEUTRAL : tone;
        this.metrics = immutable(metrics);
        this.confirmations = immutableStrings(confirmations);
        this.risks = immutableStrings(risks);
        this.nextSteps = immutableStrings(nextSteps);
        this.levels = immutable(levels);
    }

    public boolean matches(String symbol, String ltfInterval) {
        return this.symbol.equalsIgnoreCase(clean(symbol))
                && (this.ltfInterval.isBlank() || this.ltfInterval.equalsIgnoreCase(clean(ltfInterval)));
    }

    private static <T> List<T> immutable(List<T> source) {
        return source == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(source));
    }

    private static List<String> immutableStrings(List<String> source) {
        if (source == null || source.isEmpty()) return List.of();
        ArrayList<String> copy = new ArrayList<>();
        for (String value : source) {
            if (value != null && !value.isBlank()) copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public static class Metric {
        public final String label;
        public final String value;
        public final Tone tone;

        public Metric(String label, String value, Tone tone) {
            this.label = clean(label);
            this.value = clean(value);
            this.tone = tone == null ? Tone.NEUTRAL : tone;
        }
    }

    public static class LevelMarker {
        public final String label;
        public final double price;
        public final LevelType type;
        public final boolean dashed;

        public LevelMarker(String label, double price, LevelType type, boolean dashed) {
            this.label = clean(label);
            this.price = price;
            this.type = type == null ? LevelType.STRUCTURE : type;
            this.dashed = dashed;
        }
    }
}
