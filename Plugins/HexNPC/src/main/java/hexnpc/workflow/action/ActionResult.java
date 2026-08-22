package hexnpc.workflow.action;

public enum ActionResult {
    SUCCESS,
    FAILURE,
    CANCELLED;

    public boolean continues() {
        return this == SUCCESS;
    }
}
