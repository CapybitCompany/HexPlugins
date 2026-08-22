package hexnpc.workflow.action;

import hexnpc.workflow.WorkflowContext;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface WorkflowActionExecutor {
    CompletableFuture<ActionResult> execute(Player player, WorkflowAction action, WorkflowContext context);
}
