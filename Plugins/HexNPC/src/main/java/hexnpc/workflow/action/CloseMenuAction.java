package hexnpc.workflow.action;

public record CloseMenuAction() implements WorkflowAction {
    @Override public String type() { return "close_menu"; }
}
