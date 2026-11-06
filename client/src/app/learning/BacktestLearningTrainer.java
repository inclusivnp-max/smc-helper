package app.learning;

import app.common.ProjectPaths;
import app.logic.Backtester;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class BacktestLearningTrainer {
    private static final long TRAIN_TIMEOUT_SECONDS = 35L;

    public AdaptiveFactorWeights train(String symbol, Backtester.Report report, Consumer<String> log) {
        if (report == null || report.csvPath == null || report.csvPath.isBlank()) {
            emit(log, "ML trainer skipped: backtest CSV is empty");
            return null;
        }
        Path csv = Path.of(report.csvPath);
        if (!Files.exists(csv)) {
            emit(log, "ML trainer skipped: CSV not found " + csv);
            return null;
        }
        Path script = ProjectPaths.tool("train_ml_weights.py");
        if (!Files.exists(script)) {
            emit(log, "ML trainer skipped: script not found " + script);
            return null;
        }

        List<String> command = new ArrayList<>();
        command.add(resolvePython());
        command.add(script.toString());
        command.add("--symbol");
        command.add(symbol == null || symbol.isBlank() ? "UNKNOWN" : symbol);
        command.add("--backtests");
        command.add(ProjectPaths.backtestsDir().toString());
        command.add("--weights");
        command.add(ProjectPaths.weightsRoot().toString());
        command.add("--latest-csv");
        command.add(csv.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(ProjectPaths.root().toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("PYTHONUNBUFFERED", "1");

        Process process = null;
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        try {
            emit(log, "Training ML weights from backtest dataset...");
            process = builder.start();
            Process running = process;
            Future<List<String>> outputFuture = readerExecutor.submit(() -> {
                List<String> output = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        running.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.add(line);
                        if (!line.isBlank() && !line.trim().startsWith("{")) emit(log, "trainer: " + line);
                    }
                }
                return output;
            });
            boolean finished = process.waitFor(TRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                outputFuture.cancel(true);
                emit(log, "ML trainer timed out");
                return null;
            }
            outputFuture.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                emit(log, "ML trainer failed with code " + process.exitValue());
                return null;
            }
            AdaptiveFactorWeights promoted = promoteCandidate(symbol, log);
            if (promoted == null) {
                emit(log, "ML trainer did not promote a candidate model");
                return null;
            }
            emit(log, "ML weights promoted from validation: " + promoted.summary());
            return promoted;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            if (process != null && process.isAlive()) process.destroyForcibly();
            emit(log, "ML trainer failed: " + ex.getMessage());
            return null;
        } finally {
            readerExecutor.shutdownNow();
        }
    }

    private AdaptiveFactorWeights promoteCandidate(String symbol, Consumer<String> log) throws IOException {
        Path candidate = ProjectPaths.weightsRoot().resolve("ml_model.candidate.json");
        if (!Files.exists(candidate)) return null;

        String safe = safeSymbol(symbol);
        String json = Files.readString(candidate, StandardCharsets.UTF_8);
        int symbolIndex = json.indexOf("\"" + safe + "\"");
        if (symbolIndex < 0) {
            Files.deleteIfExists(candidate);
            emit(log, "ML candidate rejected: symbol not found in candidate model");
            return null;
        }

        int weightsIndex = json.indexOf("\"weights\"", symbolIndex);
        int diagnosticsIndex = json.indexOf("\"diagnostics\"", symbolIndex);
        double displacement = doubleField(json, "\"displacement\"", weightsIndex, Double.NaN);
        double volume = doubleField(json, "\"volume\"", weightsIndex, Double.NaN);
        double liquidity = doubleField(json, "\"liquidity\"", weightsIndex, Double.NaN);
        double orderFlow = doubleField(json, "\"orderFlow\"", weightsIndex, Double.NaN);
        double regime = doubleField(json, "\"regime\"", weightsIndex, Double.NaN);
        int samples = (int) Math.round(doubleField(json, "\"sampleCount\"", diagnosticsIndex, 0.0));
        double candidateExpectancy = doubleField(json, "\"validationCandidateExpectancy\"", diagnosticsIndex, Double.NaN);
        double baselineExpectancy = doubleField(json, "\"validationBaselineExpectancy\"", diagnosticsIndex, Double.NaN);

        if (samples < 50 || !validWeight(displacement) || !validWeight(volume) || !validWeight(liquidity)
                || !validWeight(orderFlow) || !validWeight(regime)
                || !Double.isFinite(candidateExpectancy) || !Double.isFinite(baselineExpectancy)
                || candidateExpectancy < baselineExpectancy) {
            Files.deleteIfExists(candidate);
            emit(log, "ML candidate rejected: samples=" + samples
                    + " validation=" + candidateExpectancy + " baseline=" + baselineExpectancy);
            return null;
        }

        AdaptiveFactorWeights weights = new AdaptiveFactorWeights(safe,
                displacement, volume, liquidity, orderFlow, regime, System.currentTimeMillis());
        Path model = ProjectPaths.weightsRoot().resolve("ml_model.json");
        try {
            Files.move(candidate, model, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(candidate, model, StandardCopyOption.REPLACE_EXISTING);
        }
        new FactorWeightOptimizer().save(weights);
        new ModelGovernanceRegistry().recordPromotion(safe, weights, samples,
                baselineExpectancy, candidateExpectancy, model);
        return weights;
    }

    private static boolean validWeight(double value) {
        return Double.isFinite(value) && value >= 0.20 && value <= 2.20;
    }

    private static double doubleField(String json, String key, int offset, double fallback) {
        if (json == null || key == null || offset < 0) return fallback;
        int start = json.indexOf(key, offset);
        if (start < 0) return fallback;
        start = json.indexOf(':', start + key.length());
        if (start < 0) return fallback;
        start++;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length()) {
            char ch = json.charAt(end);
            if (!(Character.isDigit(ch) || ch == '-' || ch == '+' || ch == '.' || ch == 'e' || ch == 'E')) break;
            end++;
        }
        if (end <= start) return fallback;
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String safeSymbol(String symbol) {
        String text = symbol == null ? "UNKNOWN" : symbol.toUpperCase(Locale.ROOT);
        return text.replaceAll("[^A-Z0-9_\\-]", "");
    }

    private static void emit(Consumer<String> log, String line) {
        if (log != null) log.accept(line);
    }

    private static String resolvePython() {
        String property = System.getProperty("smc.python.path", "");
        if (!property.isBlank()) return property;
        String env = System.getenv("SMC_PYTHON");
        if (env != null && !env.isBlank()) return env;
        Path bundled = Path.of(System.getProperty("user.home"), ".cache", "codex-runtimes",
                "codex-primary-runtime", "dependencies", "python", "python.exe");
        if (Files.exists(bundled)) return bundled.toString();
        return "python";
    }
}
