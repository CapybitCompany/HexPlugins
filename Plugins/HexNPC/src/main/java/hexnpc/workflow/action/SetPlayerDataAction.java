package hexnpc.workflow.action;

public record SetPlayerDataAction(String key, String value) implements WorkflowAction {
    public SetPlayerDataAction {
        key = key == null ? "" : key.trim();
        value = value == null ? "" : value;
        if (key.isEmpty()) throw new IllegalArgumentException("set_player_data requires key");
    }
    @Override public String type() { return "set_player_data"; }
}
