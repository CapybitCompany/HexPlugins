package hexnpc.workflow.action;

public record DeletePlayerDataAction(String key) implements WorkflowAction {
    public DeletePlayerDataAction {
        key = key == null ? "" : key.trim();
        if (key.isEmpty()) throw new IllegalArgumentException("delete_player_data requires key");
    }
    @Override public String type() { return "delete_player_data"; }
}
