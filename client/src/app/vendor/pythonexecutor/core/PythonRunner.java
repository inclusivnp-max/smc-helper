package app.vendor.pythonexecutor.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PythonRunner {
    private final VirtualEnvManager venvManager;
    private final String defaultPython;

    public PythonRunner() {
        this("python");
    }

    public PythonRunner(String defaultPython) {
        this.defaultPython = defaultPython == null || defaultPython.isBlank() ? "python" : defaultPython;
        this.venvManager = new VirtualEnvManager();
    }

    public List<String> buildCommand(Path scriptPath, List<String> args, Path venvPath, boolean unbuffered)
            throws IOException {
        if (scriptPath == null || !Files.isRegularFile(scriptPath)) {
            throw new IOException("Python script not found: " + scriptPath);
        }

        List<String> command = new ArrayList<>();
        command.add(resolvePythonExecutable(venvPath));
        if (unbuffered) command.add("-u");
        command.add(scriptPath.toString());
        if (args != null) command.addAll(args);
        return command;
    }

    public Map<String, String> environment(Path venvPath) {
        if (venvPath == null) return Map.of();
        File venvDir = venvPath.toFile();
        if (!venvManager.isVirtualEnv(venvDir)) return Map.of();
        return venvManager.getVenvEnvironment(venvDir);
    }

    public Process startPythonScript(Path scriptPath, List<String> args, Path venvPath,
                                     Map<String, String> envVars, Path workingDir) throws IOException {
        List<String> command = buildCommand(scriptPath, args, venvPath, true);
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDir != null) builder.directory(workingDir.toFile());
        builder.redirectErrorStream(true);
        builder.environment().putAll(environment(venvPath));
        if (envVars != null) builder.environment().putAll(envVars);
        return builder.start();
    }

    public ExecutionResult runPythonScript(Path scriptPath, List<String> args, Path venvPath,
                                           Map<String, String> envVars, Path workingDir) {
        List<String> outputLines = new ArrayList<>();
        List<String> errorLines = new ArrayList<>();
        int exitCode = -1;
        try {
            Process process = startPythonScript(scriptPath, args, venvPath, envVars, workingDir);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) outputLines.add(line);
            }
            exitCode = process.waitFor();
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            errorLines.add("Error: " + ex.getMessage());
        }
        return new ExecutionResult(outputLines, errorLines, exitCode);
    }

    public ExecutionResult createVirtualEnv(Path venvPath, String pythonVersion) {
        List<String> command = new ArrayList<>();
        if (isWindows()) {
            command.add("py");
            command.add("-" + (pythonVersion == null || pythonVersion.isBlank() ? "3" : pythonVersion));
        } else {
            command.add("python" + (pythonVersion == null || pythonVersion.isBlank() ? "3" : pythonVersion));
        }
        command.add("-m");
        command.add("venv");
        command.add(venvPath.toString());
        return runProcess(command, null, null);
    }

    public ExecutionResult installPackages(Path venvPath, String... packages) {
        File pipExec = new File(venvPath.toFile(), isWindows() ? "Scripts/pip.exe" : "bin/pip");
        if (!pipExec.exists()) {
            return new ExecutionResult(List.of(), List.of("Error: pip not found in venv path"), 1);
        }
        List<String> command = new ArrayList<>();
        command.add(pipExec.getAbsolutePath());
        command.add("install");
        if (packages != null) command.addAll(Arrays.asList(packages));
        return runProcess(command, venvManager.getVenvEnvironment(venvPath.toFile()), null);
    }

    public ExecutionResult listInstalledPackages(Path venvPath) {
        File pipExec = new File(venvPath.toFile(), isWindows() ? "Scripts/pip.exe" : "bin/pip");
        if (!pipExec.exists()) {
            return new ExecutionResult(List.of(), List.of("Error: pip not found in venv path"), 1);
        }
        return runProcess(List.of(pipExec.getAbsolutePath(), "list"),
                venvManager.getVenvEnvironment(venvPath.toFile()), null);
    }

    private String resolvePythonExecutable(Path venvPath) throws IOException {
        if (venvPath == null || !Files.isDirectory(venvPath)) return defaultPython;
        File venvDir = venvPath.toFile();
        if (!venvManager.isVirtualEnv(venvDir)) return defaultPython;
        File pythonExec = venvManager.getPythonExecutable(venvDir);
        if (pythonExec == null || !pythonExec.exists()) {
            throw new IOException("Python executable not found in venv: " + venvPath);
        }
        return pythonExec.getAbsolutePath();
    }

    private ExecutionResult runProcess(List<String> command, Map<String, String> envVars, Path workingDir) {
        List<String> outputLines = new ArrayList<>();
        List<String> errorLines = new ArrayList<>();
        int exitCode = -1;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDir != null) builder.directory(workingDir.toFile());
            builder.redirectErrorStream(true);
            if (envVars != null) builder.environment().putAll(envVars);
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) outputLines.add(line);
            }
            exitCode = process.waitFor();
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            errorLines.add("Error: " + ex.getMessage());
        }
        return new ExecutionResult(outputLines, errorLines, exitCode);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
