package hexnpc.workflow.action;

public record MessageAction(String text) implements WorkflowAction {
    public MessageAction {
        text = text == null ? "" : text;
    }
    @Override public String type() { return "message"; }
}
