package hexnpc.workflow.action;

import java.util.List;

public record ConditionAction(
        List<ConditionDefinition> conditions,
        List<WorkflowAction> thenActions,
        List<WorkflowAction> elseActions
) implements WorkflowAction {
    public ConditionAction {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        thenActions = thenActions == null ? List.of() : List.copyOf(thenActions);
        elseActions = elseActions == null ? List.of() : List.copyOf(elseActions);
        if (conditions.isEmpty()) throw new IllegalArgumentException("condition action requires conditions");
    }
    @Override public String type() { return "condition"; }
}
