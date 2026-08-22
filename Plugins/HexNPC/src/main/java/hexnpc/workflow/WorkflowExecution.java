package hexnpc.workflow;

import java.util.UUID;

public record WorkflowExecution(UUID playerId, String workflowId, WorkflowContext context) {
}
