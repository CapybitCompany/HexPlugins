package hexnpc.workflow.action;

public record CommandAction(Executor executor, String command, boolean allowInputVariables) implements WorkflowAction {
    public enum Executor { CONSOLE, PLAYER }

    public CommandAction {
        executor = executor == null ? Executor.CONSOLE : executor;
        command = command == null ? "" : command.trim();
        if (command.isEmpty()) throw new IllegalArgumentException("command action requires command");
    }
    @Override public String type() { return "command"; }
}
