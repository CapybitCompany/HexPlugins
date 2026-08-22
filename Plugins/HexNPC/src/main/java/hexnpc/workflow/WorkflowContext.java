package hexnpc.workflow;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorkflowContext {
    private final UUID playerId;
    private final String workflowId;
    private final String sourceMenu;
    private final String sourceItem;
    private final String sourceClick;
    private final Map<String, String> inputs = new ConcurrentHashMap<>();
    private final Map<String, String> variables = new ConcurrentHashMap<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public WorkflowContext(UUID playerId, String workflowId, String sourceMenu, String sourceItem, String sourceClick) {
        this.playerId = playerId;
        this.workflowId = workflowId == null ? "" : workflowId;
        this.sourceMenu = sourceMenu == null ? "" : sourceMenu;
        this.sourceItem = sourceItem == null ? "" : sourceItem;
        this.sourceClick = sourceClick == null ? "" : sourceClick;
    }

    public UUID playerId() { return playerId; }
    public String workflowId() { return workflowId; }
    public String sourceMenu() { return sourceMenu; }
    public String sourceItem() { return sourceItem; }
    public String sourceClick() { return sourceClick; }
    public Map<String, String> inputs() { return inputs; }
    public Map<String, String> variables() { return variables; }
    public String input(String id) { return inputs.getOrDefault(id, ""); }
    public String variable(String id) { return variables.getOrDefault(id, ""); }
    public void putInput(String id, String value) { if (id != null) inputs.put(id, value == null ? "" : value); }
    public void putVariable(String id, String value) { if (id != null) variables.put(id, value == null ? "" : value); }
    public boolean cancelled() { return cancelled.get(); }
    public void cancel() { cancelled.set(true); }
}
