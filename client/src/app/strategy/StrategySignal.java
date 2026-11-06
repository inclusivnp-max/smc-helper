package app.strategy;

import app.model.AnalysisResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class StrategySignal {
    public enum Tone {
        LONG,
        SHORT,
        NEUTRAL,
        WARNING,
        DANGER
    }

    public enum LevelType {
        ENTRY,
        STOP,
        TARGET,
        ZONE,
        TRIGGER,
        INVALIDATION
    }

    public static class Level {
        public final String label;
        public final double price;
        public final LevelType type;

        public Level(String label, double price, LevelType type) {
            this.label = label == null ? "" : label;
            this.price = price;
            this.type = type == null ? LevelType.ZONE : type;
        }
    }

    public final String id;
    public final String name;
    public final String description;
    public final String symbol;
    public final String interval;
    public final boolean suitable;
    public final boolean longSide;
    public final int score;
    public final Tone tone;
    public final String verdict;
    public final String action;
    public final List<String> confirmations;
    public final List<String> blockers;
    public final List<Level> levels;

    public StrategySignal(String id,
                          String name,
                          String description,
                          String symbol,
                          String interval,
                          boolean suitable,
                          boolean longSide,
                          int score,
                          Tone tone,
                          String verdict,
                          String action,
                          List<String> confirmations,
                          List<String> blockers,
                          List<Level> levels) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.description = description == null ? "" : description;
        this.symbol = symbol == null ? "" : symbol;
        this.interval = interval == null ? "" : interval;
        this.suitable = suitable;
        this.longSide = longSide;
        this.score = Math.max(0, Math.min(100, score));
        this.tone = tone == null ? Tone.NEUTRAL : tone;
        this.verdict = verdict == null ? "" : verdict;
        this.action = action == null ? "" : action;
        this.confirmations = immutable(confirmations);
        this.blockers = immutable(blockers);
        this.levels = immutable(levels);
    }

    public boolean matches(AnalysisResult result) {
        return result != null
                && result.symbol != null
                && result.ltfInterval != null
                && result.symbol.equalsIgnoreCase(symbol)
                && result.ltfInterval.equalsIgnoreCase(interval);
    }

    public String statusText() {
        if (suitable) return "подходит · " + score + "/100";
        return "не подходит · " + score + "/100";
    }

    private static <T> List<T> immutable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<>(source == null ? List.of() : source));
    }
}
