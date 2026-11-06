package app.learning;

import app.common.ProjectPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ModelGovernanceRegistry {
    private final Path path = ProjectPaths.modelGovernanceDir().resolve("model-promotions.jsonl");

    public void recordPromotion(String symbol,
                                AdaptiveFactorWeights weights,
                                int samples,
                                double baselineExpectancy,
                                double candidateExpectancy,
                                Path modelPath) {
        if (weights == null) return;
        append("{\"time\":" + System.currentTimeMillis()
                + ",\"symbol\":\"" + esc(symbol) + "\""
                + ",\"samples\":" + samples
                + ",\"baselineExpectancy\":" + num(baselineExpectancy)
                + ",\"candidateExpectancy\":" + num(candidateExpectancy)
                + ",\"edgeDelta\":" + num(candidateExpectancy - baselineExpectancy)
                + ",\"weights\":{"
                + "\"displacement\":" + num(weights.displacementWeight)
                + ",\"volume\":" + num(weights.volumeWeight)
                + ",\"liquidity\":" + num(weights.liquidityWeight)
                + ",\"orderFlow\":" + num(weights.orderFlowWeight)
                + ",\"regime\":" + num(weights.regimeWeight)
                + "}"
                + ",\"modelPath\":\"" + esc(modelPath == null ? "" : modelPath.toString()) + "\""
                + "}");
    }

    public GovernanceSummary summary() {
        GovernanceSummary summary = new GovernanceSummary(path);
        if (!Files.exists(path)) return summary;
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            summary.promotions = lines.size();
            int start = Math.max(0, lines.size() - 5);
            summary.recent.addAll(lines.subList(start, lines.size()));
            summary.bytes = Files.size(path);
        } catch (IOException ex) {
            summary.errors++;
        }
        return summary;
    }

    private void append(String line) {
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException ignored) {
        }
    }

    private static String num(double value) {
        if (!Double.isFinite(value)) return "null";
        return String.format(Locale.US, "%.8f", value);
    }

    private static String esc(String value) {
        String text = value == null ? "" : value;
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    public static class GovernanceSummary {
        public final Path path;
        public int promotions;
        public int errors;
        public long bytes;
        public final List<String> recent = new ArrayList<>();

        GovernanceSummary(Path path) {
            this.path = path;
        }

        public List<String> lines() {
            List<String> lines = new ArrayList<>();
            lines.add("Model governance: " + path);
            lines.add("Promotions: " + promotions + " / errors " + errors + " / size " + bytes + " bytes");
            lines.add("Checked: " + Instant.now());
            if (!recent.isEmpty()) lines.add("Recent: " + recent.get(recent.size() - 1));
            return lines;
        }
    }
}
