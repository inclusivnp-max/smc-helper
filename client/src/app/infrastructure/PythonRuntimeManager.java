package app.infrastructure;

import app.common.ProjectPaths;
import app.vendor.pythonexecutor.core.PythonRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class PythonRuntimeManager {
    public static final String ML_BRIDGE_URL = "http://127.0.0.1:8765/weights";
    public static final String ML_BRIDGE_FAST_URL = "tcp://127.0.0.1:8766";
    private static final DateTimeFormatter LOG_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final Map<String, PythonTask> tasks = new ConcurrentHashMap<>();

    public String runnerLabel() {
        if (!externalJarMode()) {
            return Files.isDirectory(ProjectPaths.pythonVenvDir())
                    ? "embedded java-python-executor + local venv"
                    : "embedded java-python-executor (" + pythonExecutable() + ")";
        }
        if (!Files.exists(ProjectPaths.javaPythonExecutorJar())) return "external JAR requested but not found";
        String fileName = ProjectPaths.javaPythonExecutorJar().getFileName().toString();
        return Files.isDirectory(ProjectPaths.pythonVenvDir())
                ? fileName + " + local venv"
                : fileName;
    }

    public PythonTask startMlService(Consumer<String> log) throws IOException {
        System.setProperty("smc.ml.bridge.url", ML_BRIDGE_URL);
        System.setProperty("smc.ml.bridge.fast.url", ML_BRIDGE_FAST_URL);
        System.setProperty("smc.ml.bridge.transport", "binary");
        return start("ML service", ProjectPaths.tool("ml_weight_service.py"), List.of(), log);
    }

    public PythonTask startTickerPlant(String symbols, Consumer<String> log) throws IOException {
        String normalized = symbols == null || symbols.isBlank() ? "BTCUSDT,ETHUSDT" : symbols.trim();
        return start("Ticker plant", ProjectPaths.tool("local_ticker_plant.py"),
                List.of("--symbols", normalized, "--root", ProjectPaths.dataLakeRoot().toString()),
                log);
    }

    public PythonTask start(String name, Path script, List<String> args, Consumer<String> log) throws IOException {
        Files.createDirectories(ProjectPaths.pythonLogsDir());
        appendDebug("START requested name=" + name + " script=" + script + " args=" + (args == null ? List.of() : args));
        if (!Files.exists(script)) {
            IOException ex = new IOException("Python script not found: " + script);
            appendDebug("START rejected name=" + name + " reason=" + ex.getMessage());
            throw ex;
        }
        PythonTask existing = tasks.get(name);
        if (existing != null && existing.running()) {
            if (log != null) log.accept(name + " already running");
            appendDebug("START skipped name=" + name + " reason=already_running pid=" + existing.process.pid()
                    + " log=" + existing.logPath);
            return existing;
        }

        Files.createDirectories(ProjectPaths.pythonRuntimeDir());
        Files.createDirectories(ProjectPaths.dataLakeRoot());
        Files.createDirectories(ProjectPaths.pythonLogsDir());
        Path taskLog = taskLogPath(name);
        List<String> command = buildCommand(script, args == null ? List.of() : args);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(ProjectPaths.root().toFile());
        builder.redirectErrorStream(true);
        if (!externalJarMode() && Files.isDirectory(ProjectPaths.pythonVenvDir())) {
            builder.environment().putAll(new PythonRunner(pythonExecutable()).environment(ProjectPaths.pythonVenvDir()));
        }
        builder.environment().put("SMC_PROJECT_ROOT", ProjectPaths.root().toString());
        builder.environment().put("SMC_DATA_LAKE_ROOT", ProjectPaths.dataLakeRoot().toString());
        builder.environment().put("SMC_ML_BRIDGE_URL", ML_BRIDGE_URL);
        builder.environment().put("SMC_ML_BRIDGE_FAST_URL", ML_BRIDGE_FAST_URL);
        builder.environment().put("SMC_ML_BRIDGE_TRANSPORT", "binary");
        builder.environment().put("PYTHONUNBUFFERED", "1");

        appendTaskLog(taskLog, "Launching " + name);
        appendTaskLog(taskLog, "Runner: " + runnerLabel());
        appendTaskLog(taskLog, "Command: " + String.join(" ", command));
        appendTaskLog(taskLog, "Working dir: " + ProjectPaths.root());
        appendTaskLog(taskLog, "Script exists: " + Files.exists(script) + " size=" + safeSize(script));
        appendTaskLog(taskLog, "Venv: " + (Files.isDirectory(ProjectPaths.pythonVenvDir()) ? ProjectPaths.pythonVenvDir() : "none"));
        appendTaskLog(taskLog, "Env: " + envSummary(builder.environment()));
        appendDebug("START prepared name=" + name + " command=" + String.join(" ", command)
                + " workDir=" + ProjectPaths.root() + " taskLog=" + taskLog);

        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            appendTaskLog(taskLog, "START FAILED: " + ex.getMessage());
            appendDebug("START failed name=" + name + " reason=" + ex.getMessage());
            throw ex;
        }
        PythonTask task = new PythonTask(name, String.join(" ", command), taskLog, process);
        tasks.put(name, task);
        pipeOutput(task, log);
        appendTaskLog(taskLog, "Started PID: " + process.pid());
        appendDebug("START ok name=" + name + " pid=" + process.pid() + " taskLog=" + taskLog);
        if (log != null) {
            log.accept("Started " + name + ": " + task.commandLine);
            log.accept("Debug log: " + task.logPath);
        }
        return task;
    }

    public void stopAll() {
        for (PythonTask task : tasks.values()) {
            appendDebug("STOP requested name=" + task.name + " running=" + task.running() + " pid="
                    + (task.process == null ? "-" : task.process.pid()));
            appendTaskLog(task.logPath, "Stop requested. running=" + task.running());
            task.stop();
        }
    }

    public List<PythonTask> tasks() {
        return Collections.unmodifiableList(new ArrayList<>(tasks.values()));
    }

    private List<String> buildCommand(Path script, List<String> args) {
        if (externalJarMode() && Files.exists(ProjectPaths.javaPythonExecutorJar())) {
            List<String> command = new ArrayList<>();
            command.add(resolveJava());
            command.add("-jar");
            command.add(ProjectPaths.javaPythonExecutorJar().toString());
            command.add(script.toString());
            if (Files.isDirectory(ProjectPaths.pythonVenvDir())) {
                command.add("--venv");
                command.add(ProjectPaths.pythonVenvDir().toString());
            }
            if (!args.isEmpty()) command.add("--");
            command.addAll(args);
            return command;
        }

        try {
            Path venv = Files.isDirectory(ProjectPaths.pythonVenvDir()) ? ProjectPaths.pythonVenvDir() : null;
            return new PythonRunner(pythonExecutable()).buildCommand(script, args, venv, true);
        } catch (IOException ex) {
            appendDebug("buildCommand fallback script=" + script + " reason=" + ex.getMessage());
            List<String> fallback = new ArrayList<>();
            fallback.add(pythonExecutable());
            fallback.add("-u");
            fallback.add(script.toString());
            fallback.addAll(args);
            return fallback;
        }
    }

    public String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("App folder: ").append(ProjectPaths.root()).append('\n');
        sb.append("Data lake: ").append(ProjectPaths.dataLakeRoot()).append('\n');
        sb.append("Python runtime: ").append(ProjectPaths.pythonRuntimeDir()).append('\n');
        sb.append("Debug logs: ").append(ProjectPaths.pythonLogsDir()).append('\n');
        sb.append("Runner: ").append(runnerLabel()).append('\n');
        sb.append("ML bridge: binary ").append(ML_BRIDGE_FAST_URL)
                .append(" | HTTP fallback ").append(ML_BRIDGE_URL).append('\n');
        if (tasks.isEmpty()) {
            sb.append("Tasks: none\n");
        } else {
            sb.append("Tasks:\n");
            for (PythonTask task : tasks()) {
                sb.append(" - ").append(task.name)
                        .append(task.running() ? " RUNNING" : " STOPPED")
                        .append(" | ").append(task.commandLine)
                        .append(" | log ").append(task.logPath)
                        .append('\n');
            }
        }
        return sb.toString();
    }

    public Map<String, String> runtimeEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("SMC_PROJECT_ROOT", ProjectPaths.root().toString());
        env.put("SMC_DATA_LAKE_ROOT", ProjectPaths.dataLakeRoot().toString());
        env.put("SMC_ML_BRIDGE_URL", ML_BRIDGE_URL);
        env.put("SMC_ML_BRIDGE_FAST_URL", ML_BRIDGE_FAST_URL);
        env.put("SMC_ML_BRIDGE_TRANSPORT", "binary");
        env.put("PYTHONUNBUFFERED", "1");
        if (!externalJarMode() && Files.isDirectory(ProjectPaths.pythonVenvDir())) {
            env.putAll(new PythonRunner(pythonExecutable()).environment(ProjectPaths.pythonVenvDir()));
        }
        return env;
    }

    private boolean externalJarMode() {
        return Boolean.getBoolean("smc.pythonExecutor.externalJar");
    }

    private String pythonExecutable() {
        String property = System.getProperty("smc.python.path", "");
        if (!property.isBlank()) return property;
        String env = System.getenv("SMC_PYTHON");
        if (env != null && !env.isBlank()) return env;
        Path bundled = Path.of(System.getProperty("user.home"), ".cache", "codex-runtimes",
                "codex-primary-runtime", "dependencies", "python", "python.exe");
        if (Files.exists(bundled)) return bundled.toString();
        return "python";
    }

    private String resolveJava() {
        Path windows = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (Files.exists(windows)) return windows.toString();
        Path unix = Path.of(System.getProperty("java.home"), "bin", "java");
        return Files.exists(unix) ? unix.toString() : "java";
    }

    private void pipeOutput(PythonTask task, Consumer<String> log) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    task.process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendTaskLog(task.logPath, "[stdout] " + line);
                    if (log != null) log.accept("[" + task.name + "] " + line);
                }
                int exitCode = task.process.waitFor();
                appendTaskLog(task.logPath, "Process exited with code " + exitCode);
                appendDebug("EXIT name=" + task.name + " pid=" + task.process.pid() + " code=" + exitCode);
            } catch (IOException ex) {
                appendTaskLog(task.logPath, "Output reader failed: " + ex.getMessage());
                appendDebug("OUTPUT reader failed name=" + task.name + " reason=" + ex.getMessage());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                appendTaskLog(task.logPath, "Output reader interrupted");
                appendDebug("OUTPUT reader interrupted name=" + task.name);
            }
        }, "SMC-Python-" + task.name.replace(' ', '-'));
        thread.setDaemon(true);
        thread.start();
    }

    private Path taskLogPath(String name) {
        String safeName = name == null ? "python-task" : name.toLowerCase()
                .replaceAll("[^a-z0-9_\\-]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (safeName.isBlank()) safeName = "python-task";
        return ProjectPaths.pythonLogsDir().resolve(safeName + "-" + FILE_STAMP.format(LocalDateTime.now()) + ".log");
    }

    private void appendDebug(String line) {
        appendLog(ProjectPaths.pythonLogsDir().resolve("runtime.log"), line);
    }

    private void appendTaskLog(Path logPath, String line) {
        if (logPath != null) appendLog(logPath, line);
    }

    private void appendLog(Path path, String line) {
        try {
            Files.createDirectories(path.getParent());
            String stamped = LOG_STAMP.format(LocalDateTime.now()) + " | " + line + System.lineSeparator();
            Files.writeString(path, stamped, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return -1L;
        }
    }

    private String envSummary(Map<String, String> env) {
        List<String> values = new ArrayList<>();
        for (String key : List.of("SMC_PROJECT_ROOT", "SMC_DATA_LAKE_ROOT", "SMC_ML_BRIDGE_URL",
                "PYTHONUNBUFFERED", "VIRTUAL_ENV")) {
            if (env.containsKey(key)) values.add(key + "=" + env.get(key));
        }
        if (env.containsKey("PATH")) values.add("PATH=<set>");
        return values.toString();
    }
}
