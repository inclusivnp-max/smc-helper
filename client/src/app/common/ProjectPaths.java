package app.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ProjectPaths {
    private static Path cachedRoot;

    public static Path root() {
        if (cachedRoot != null) return cachedRoot;
        String property = System.getProperty("smc.project.root", "");
        if (!property.isBlank()) return cachedRoot = Paths.get(property).toAbsolutePath().normalize();
        String env = System.getenv("SMC_PROJECT_ROOT");
        if (env != null && !env.isBlank()) return cachedRoot = Paths.get(env).toAbsolutePath().normalize();

        Path packagedAppDir = packagedApplicationDir();
        if (packagedAppDir != null) return cachedRoot = packagedAppDir;

        Path current = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path project = findProjectRoot(current);
        if (project != null) return cachedRoot = project;

        Path appDir = applicationDir();
        if (appDir != null) {
            project = findProjectRoot(appDir);
            if (project != null) return cachedRoot = project;
            return cachedRoot = appDir;
        }
        return cachedRoot = current;
    }

    public static Path toolsDir() {
        return root().resolve("tools");
    }

    public static Path tool(String fileName) {
        return toolsDir().resolve(fileName);
    }

    public static Path dataLakeRoot() {
        return overridePath("smc.dataLake.root", "SMC_DATA_LAKE_ROOT", root().resolve("data-lake"));
    }

    public static Path tickCacheRoot() {
        return overridePath("smc.tickCache.root", "SMC_TICK_CACHE_ROOT", root().resolve("tick-cache"));
    }

    public static Path websocketTicksRoot() {
        return overridePath("smc.websocketTicks.root", "SMC_WEBSOCKET_TICKS_ROOT", root().resolve("websocket-ticks"));
    }

    public static Path weightsRoot() {
        return overridePath("smc.weights.root", "SMC_WEIGHTS_ROOT", root().resolve("weights"));
    }

    public static Path backtestsDir() {
        return overridePath("smc.backtests.dir", "SMC_BACKTESTS_DIR", root().resolve("backtests"));
    }

    public static Path backtestEvidenceDir() {
        return overridePath("smc.backtestEvidence.dir", "SMC_BACKTEST_EVIDENCE_DIR",
                root().resolve("backtest-evidence"));
    }

    public static Path executionJournalDir() {
        return overridePath("smc.executionJournal.dir", "SMC_EXECUTION_JOURNAL_DIR",
                root().resolve("execution-journal"));
    }

    public static Path closedTradesDir() {
        return overridePath("smc.closedTrades.dir", "SMC_CLOSED_TRADES_DIR",
                root().resolve("closed-trades"));
    }

    public static Path modelGovernanceDir() {
        return overridePath("smc.modelGovernance.dir", "SMC_MODEL_GOVERNANCE_DIR",
                root().resolve("model-governance"));
    }

    public static Path dataQualityDir() {
        return overridePath("smc.dataQuality.dir", "SMC_DATA_QUALITY_DIR",
                root().resolve("data-quality"));
    }

    public static Path pythonRuntimeDir() {
        return root().resolve("python-runtime");
    }

    public static Path pythonLogsDir() {
        return pythonRuntimeDir().resolve("logs");
    }

    public static Path javaPythonExecutorJar() {
        String property = System.getProperty("smc.pythonExecutor.jar", "");
        if (!property.isBlank()) return Paths.get(property).toAbsolutePath().normalize();
        String env = System.getenv("SMC_PYTHON_EXECUTOR_JAR");
        if (env != null && !env.isBlank()) return Paths.get(env).toAbsolutePath().normalize();

        Path[] candidates = {
                root().resolve("java-python-executor.jar"),
                root().resolve("python-java-executor.jar"),
                root().resolve("Python java executor.jar"),
                toolsDir().resolve("java-python-executor.jar"),
                toolsDir().resolve("python-java-executor.jar"),
                toolsDir().resolve("Python java executor.jar")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) return candidate.toAbsolutePath().normalize();
        }
        return root().resolve("java-python-executor.jar").toAbsolutePath().normalize();
    }

    public static Path pythonVenvDir() {
        return pythonRuntimeDir().resolve("venv");
    }

    private static Path overridePath(String propertyName, String envName, Path fallback) {
        String property = System.getProperty(propertyName, "");
        if (!property.isBlank()) return Paths.get(property).toAbsolutePath().normalize();
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) return Paths.get(env).toAbsolutePath().normalize();
        return fallback.toAbsolutePath().normalize();
    }

    private static Path findProjectRoot(Path start) {
        Path cursor = start;
        while (cursor != null) {
            if (Files.isDirectory(cursor.resolve("src").resolve("app"))
                    || Files.isDirectory(cursor.resolve("tools"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private static Path applicationDir() {
        try {
            Path location = Paths.get(ProjectPaths.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()).toAbsolutePath().normalize();
            if (Files.isRegularFile(location)) return location.getParent();
            if (Files.isDirectory(location)) return location;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Path packagedApplicationDir() {
        try {
            Path location = Paths.get(ProjectPaths.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()).toAbsolutePath().normalize();
            if (Files.isRegularFile(location)) return location.getParent();
        } catch (Exception ignored) {
        }
        return null;
    }
}
