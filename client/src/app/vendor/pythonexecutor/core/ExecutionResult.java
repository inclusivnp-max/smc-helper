package app.vendor.pythonexecutor.core;

import java.util.List;

public class ExecutionResult {
    private final List<String> output;
    private final List<String> errors;
    private final int exitCode;

    public ExecutionResult(List<String> output, List<String> errors, int exitCode) {
        this.output = output == null ? List.of() : List.copyOf(output);
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.exitCode = exitCode;
    }

    public List<String> getOutput() {
        return output;
    }

    public List<String> getErrors() {
        return errors;
    }

    public int getExitCode() {
        return exitCode;
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }

    @Override
    public String toString() {
        return "ExecutionResult{" +
                "output=" + output +
                ", errors=" + errors +
                ", exitCode=" + exitCode +
                '}';
    }
}
