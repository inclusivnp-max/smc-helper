package app.vendor.pythonexecutor.core;

import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VirtualEnvManager {
    public List<File> findVirtualEnvs(Path searchPath) {
        List<File> venvs = new ArrayList<>();
        if (searchPath == null || !Files.isDirectory(searchPath)) return venvs;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(searchPath)) {
            for (Path path : stream) {
                File file = path.toFile();
                if (file.isDirectory() && isVirtualEnv(file)) {
                    venvs.add(file);
                }
            }
        } catch (Exception ignored) {
        }
        return venvs;
    }

    public boolean isVirtualEnv(File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] checks = {
                new File(dir, "pyvenv.cfg"),
                new File(dir, "bin/activate"),
                new File(dir, "Scripts/activate"),
                new File(dir, "bin/python"),
                new File(dir, "Scripts/python.exe")
        };

        for (File check : checks) {
            if (check.exists()) return true;
        }
        return false;
    }

    public File getPythonExecutable(File venvDir) {
        if (venvDir == null) return null;

        File unixPython = new File(venvDir, "bin/python");
        if (unixPython.exists()) return unixPython;

        File windowsPython = new File(venvDir, "Scripts/python.exe");
        if (windowsPython.exists()) return windowsPython;

        File[] possibleDirs = {
                new File(venvDir, "bin"),
                new File(venvDir, "Scripts")
        };
        for (File possibleDir : possibleDirs) {
            if (!possibleDir.exists() || !possibleDir.isDirectory()) continue;
            File[] files = possibleDir.listFiles((dir, name) ->
                    name.startsWith("python")
                            && !name.contains("config")
                            && (name.endsWith(".exe") || !name.contains(".")));
            if (files != null && files.length > 0) return files[0];
        }
        return null;
    }

    public Map<String, String> getVenvEnvironment(File venvDir) {
        Map<String, String> env = new HashMap<>();
        File pythonExec = getPythonExecutable(venvDir);
        if (pythonExec == null || !pythonExec.exists()) return env;

        String pathAddition = pythonExec.getParent();
        env.put("VIRTUAL_ENV", venvDir.getAbsolutePath());
        String currentPath = System.getenv("PATH");
        String separator = isWindows() ? ";" : ":";
        env.put("PATH", pathAddition + separator + (currentPath == null ? "" : currentPath));
        return env;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
