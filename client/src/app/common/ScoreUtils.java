package app.common;

public class ScoreUtils {
    private ScoreUtils() {}

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int scale(double value, double min, double max, int points) {
        if (max <= min) return 0;
        double normalized = (value - min) / (max - min);
        return (int) Math.round(clamp(normalized, 0.0, 1.0) * points);
    }
}
