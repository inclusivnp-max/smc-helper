package app.vendor.pythonexecutor.cli;

import java.util.ArrayList;
import java.util.List;

public class CommandLineArgs {
    private String scriptPath;
    private final List<String> scriptArgs = new ArrayList<>();
    private String venvPath;
    private boolean helpRequested;
    private boolean listEnvs;
    private boolean createEnv;
    private String envName;
    private String pythonVersion;
    private final List<String> packages = new ArrayList<>();

    public static CommandLineArgs parse(String[] args) {
        CommandLineArgs cli = new CommandLineArgs();
        if (args == null || args.length == 0) {
            cli.helpRequested = true;
            return cli;
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> cli.helpRequested = true;
                case "-v", "--venv" -> {
                    if (i + 1 < args.length) cli.venvPath = args[++i];
                }
                case "-l", "--list-envs" -> cli.listEnvs = true;
                case "-c", "--create-env" -> {
                    cli.createEnv = true;
                    if (i + 1 < args.length && !args[i + 1].startsWith("-")) cli.envName = args[++i];
                }
                case "--python" -> {
                    if (i + 1 < args.length) cli.pythonVersion = args[++i];
                }
                case "--install" -> {
                    while (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        cli.packages.add(args[++i]);
                    }
                }
                case "--" -> {
                    for (int j = i + 1; j < args.length; j++) cli.scriptArgs.add(args[j]);
                    i = args.length;
                }
                default -> {
                    if (cli.scriptPath == null && !arg.startsWith("-")) {
                        cli.scriptPath = arg;
                    } else if (!arg.startsWith("-") && cli.scriptPath != null) {
                        cli.scriptArgs.add(arg);
                    }
                }
            }
        }
        return cli;
    }

    public String getScriptPath() { return scriptPath; }
    public List<String> getScriptArgs() { return scriptArgs; }
    public String getVenvPath() { return venvPath; }
    public boolean isHelpRequested() { return helpRequested; }
    public boolean isListEnvs() { return listEnvs; }
    public boolean isCreateEnv() { return createEnv; }
    public String getEnvName() { return envName; }
    public String getPythonVersion() { return pythonVersion; }
    public List<String> getPackages() { return packages; }
    public boolean hasScript() { return scriptPath != null; }
    public boolean hasVenv() { return venvPath != null && !venvPath.isBlank(); }
}
