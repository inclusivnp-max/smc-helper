package app.vendor.pythonexecutor.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PythonService {
    private final PythonRunner runner;
    private final VirtualEnvManager venvManager;
    private final Map<String, String> configuredEnvs;
    private final Path configFile;

    public PythonService(Path configFile, String defaultPython) {
        this.runner = new PythonRunner(defaultPython);
        this.venvManager = new VirtualEnvManager();
        this.configuredEnvs = new HashMap<>();
        this.configFile = configFile;
        loadConfiguredEnvs();
    }

    public ExecutionResult setupVenv(String envName, Path basePath, String pythonVersion, String... packages) {
        Path venvPath = basePath.resolve(envName);
        ExecutionResult createResult = runner.createVirtualEnv(venvPath, pythonVersion);
        if (!createResult.isSuccess()) return createResult;

        if (packages != null && packages.length > 0) {
            ExecutionResult installResult = runner.installPackages(venvPath, packages);
            if (!installResult.isSuccess()) return installResult;
        }
        saveVenv(envName, venvPath.toString());
        return new ExecutionResult(
                List.of("Environment '" + envName + "' created successfully in: " + venvPath),
                List.of(),
                0);
    }

    public Map<String, String> listVenvs() {
        return new HashMap<>(configuredEnvs);
    }

    public boolean validateVenv(String envName) {
        String venvPath = configuredEnvs.get(envName);
        if (venvPath == null) return false;
        Path path = Path.of(venvPath);
        return venvManager.isVirtualEnv(path.toFile())
                && venvManager.getPythonExecutable(path.toFile()) != null;
    }

    public ExecutionResult runExternalScriptInVenv(String envName, Path scriptPath, String... args) {
        String venvPath = configuredEnvs.get(envName);
        if (venvPath == null) {
            return new ExecutionResult(List.of(),
                    List.of("Error: Environment '" + envName + "' not configured"),
                    1);
        }
        return runner.runPythonScript(scriptPath,
                args == null ? List.of() : Arrays.asList(args),
                Path.of(venvPath), null, null);
    }

    public ExecutionResult runExternalScript(Path scriptPath, String... args) {
        return runner.runPythonScript(scriptPath,
                args == null ? List.of() : Arrays.asList(args),
                null, null, null);
    }

    public ExecutionResult runWithEnvironmentVars(Path scriptPath, Map<String, String> envVars, String... args) {
        return runner.runPythonScript(scriptPath,
                args == null ? List.of() : Arrays.asList(args),
                null, envVars, null);
    }

    private void loadConfiguredEnvs() {
        if (configFile == null || !Files.exists(configFile)) return;
        try (BufferedReader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2) configuredEnvs.put(parts[0].trim(), parts[1].trim());
            }
        } catch (IOException ignored) {
        }
    }

    private void saveVenv(String name, String path) {
        configuredEnvs.put(name, path);
        saveConfigurations();
    }

    private void saveConfigurations() {
        if (configFile == null) return;
        try {
            Files.createDirectories(configFile.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
                writer.write("# Python virtual environments configured by SMC Chart");
                writer.newLine();
                for (Map.Entry<String, String> entry : configuredEnvs.entrySet()) {
                    writer.write(entry.getKey() + "=" + entry.getValue());
                    writer.newLine();
                }
            }
        } catch (IOException ignored) {
        }
    }
}
