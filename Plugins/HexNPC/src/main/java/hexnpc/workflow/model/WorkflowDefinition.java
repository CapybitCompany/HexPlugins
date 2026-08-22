package hexnpc.workflow.model;

import hexnpc.workflow.action.WorkflowAction;

import java.util.List;

public record WorkflowDefinition(String id, List<WorkflowAction> actions) {
    public WorkflowDefinition {
        id = id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
        actions = actions == null ? List.of() : List.copyOf(actions);
        if (id.isEmpty()) throw new IllegalArgumentException("workflow id is blank");
        if (actions.isEmpty()) throw new IllegalArgumentException("workflow '" + id + "' has no actions");
    }
}
