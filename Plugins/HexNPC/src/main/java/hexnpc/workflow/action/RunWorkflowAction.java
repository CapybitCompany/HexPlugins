package hexnpc.workflow.action;

public record RunWorkflowAction(String workflow) implements WorkflowAction {
    public RunWorkflowAction {
        workflow = workflow == null ? "" : workflow.trim();
        if (workflow.isEmpty()) throw new IllegalArgumentException("run_workflow requires workflow");
    }
    @Override public String type() { return "run_workflow"; }
}
