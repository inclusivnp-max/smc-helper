package app.infrastructure;

import java.nio.file.Path;

public class PythonTask {
    public final String name;
    public final String commandLine;
    public final Path logPath;
    public final long startedAt;
    public final Process process;

    PythonTask(String name, String commandLine, Path logPath, Process process) {
        this.name = name;
        this.commandLine = commandLine;
        this.logPath = logPath;
        this.process = process;
        this.startedAt = System.currentTimeMillis();
    }

    public boolean running() {
        return process != null && process.isAlive();
    }

    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }
}
