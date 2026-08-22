package hexnpc.workflow;

import hexnpc.workflow.action.ActionResult;
import hexnpc.workflow.action.WorkflowAction;
import hexnpc.workflow.action.WorkflowActionExecutor;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ActionRegistry {
    private final Map<String, WorkflowActionExecutor> executors = new ConcurrentHashMap<>();

    public void register(String type, WorkflowActionExecutor executor) {
        if (type == null || type.isBlank() || executor == null) return;
        executors.put(normalize(type), executor);
    }

    public boolean contains(String type) {
        return executors.containsKey(normalize(type));
    }

    public CompletableFuture<ActionResult> execute(Player player, WorkflowAction action, WorkflowContext context) {
        if (action == null) return CompletableFuture.completedFuture(ActionResult.FAILURE);
        WorkflowActionExecutor executor = executors.get(normalize(action.type()));
        if (executor == null) return CompletableFuture.completedFuture(ActionResult.FAILURE);
        try {
            CompletableFuture<ActionResult> future = executor.execute(player, action, context);
            return future == null ? CompletableFuture.completedFuture(ActionResult.FAILURE) : future;
        } catch (Throwable t) {
            CompletableFuture<ActionResult> failed = new CompletableFuture<>();
            failed.completeExceptionally(t);
            return failed;
        }
    }

    private String normalize(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
