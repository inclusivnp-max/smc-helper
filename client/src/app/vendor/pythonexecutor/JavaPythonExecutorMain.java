package app.vendor.pythonexecutor;

import app.vendor.pythonexecutor.cli.CommandLineArgs;
import app.vendor.pythonexecutor.core.ExecutionResult;
import app.vendor.pythonexecutor.core.PythonRunner;
import app.vendor.pythonexecutor.core.PythonService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class JavaPythonExecutorMain {
    private static final String APP_NAME = "Embedded Java Python Executor";
    private static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        CommandLineArgs cli = CommandLineArgs.parse(args);
        if (cli.isHelpRequested() || (args.length == 0 && !cli.hasScript())) {
            printHelp();
            return;
        }
        if (cli.isListEnvs()) {
            PythonService service = service();
            service.listVenvs().forEach((name, path) ->
                    System.out.println(name + "=" + path + " valid=" + service.validateVenv(name)));
            return;
        }
        if (cli.isCreateEnv()) {
            if (cli.getEnvName() == null || cli.getEnvName().isBlank()) {
                System.err.println("Missing environment name");
                System.exit(1);
            }
            ExecutionResult result = service().setupVenv(
                    cli.getEnvName(),
                    Path.of("python-runtime"),
                    cli.getPythonVersion() == null ? "3" : cli.getPythonVersion(),
                    cli.getPackages().toArray(new String[0]));
            printResult(result);
            System.exit(result.getExitCode());
        }
        if (!cli.hasScript()) {
            System.err.println("No Python script specified");
            System.exit(1);
        }
        Path script = Path.of(cli.getScriptPath());
        if (!Files.isRegularFile(script)) {
            System.err.println("Python script not found: " + script);
            System.exit(1);
        }
        Path venv = cli.hasVenv() ? Path.of(cli.getVenvPath()) : null;
        ExecutionResult result = new PythonRunner().runPythonScript(
                script,
                cli.getScriptArgs() == null ? List.of() : cli.getScriptArgs(),
                venv,
                null,
                null);
        printResult(result);
        System.exit(result.getExitCode());
    }

    private static PythonService service() {
        return new PythonService(Path.of("python-runtime", "python-envs.properties"), "python");
    }

    private static void printResult(ExecutionResult result) {
        result.getOutput().forEach(System.out::println);
        result.getErrors().forEach(System.err::println);
        System.out.println("exitCode=" + result.getExitCode());
    }

    private static void printHelp() {
        System.out.println(APP_NAME + " v" + VERSION);
        System.out.println("Usage: java ... " + JavaPythonExecutorMain.class.getName()
                + " [options] script.py [args]");
        System.out.println("Options:");
        System.out.println("  -v, --venv PATH");
        System.out.println("  -l, --list-envs");
        System.out.println("  -c, --create-env NAME --python 3 --install numpy pandas");
        System.out.println("  -- separates executor options from Python script args");
    }
}
