package hexnpc.workflow.model;

import java.util.Map;

public record WorkflowMenu(
        String id,
        String title,
        int size,
        WorkflowMenuBackground background,
        Map<String, WorkflowMenuItem> items
) {
    public WorkflowMenu {
        id = id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
        title = title == null ? "&0Menu" : title;
        if (id.isEmpty()) throw new IllegalArgumentException("menu id is blank");
        if (size <= 0 || size > 54 || size % 9 != 0) throw new IllegalArgumentException("invalid menu size " + size);
        background = background == null ? WorkflowMenuBackground.defaults() : background;
        items = items == null ? Map.of() : Map.copyOf(items);
    }
}
