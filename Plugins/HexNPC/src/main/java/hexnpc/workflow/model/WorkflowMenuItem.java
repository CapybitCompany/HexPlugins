package hexnpc.workflow.model;

import hexnpc.workflow.action.WorkflowAction;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;

public record WorkflowMenuItem(
        String id,
        int slot,
        Material material,
        Integer customModelData,
        String name,
        List<String> lore,
        Map<String, List<WorkflowAction>> actions
) {
    public WorkflowMenuItem {
        id = id == null ? "" : id.trim();
        if (id.isEmpty()) throw new IllegalArgumentException("menu item id is blank");
        if (slot < 0) throw new IllegalArgumentException("menu item slot < 0");
        material = material == null ? Material.STONE : material;
        name = name == null ? "" : name;
        lore = lore == null ? List.of() : List.copyOf(lore);
        actions = actions == null ? Map.of() : Map.copyOf(actions);
    }

    public List<WorkflowAction> actionsFor(String click) {
        if (click == null) return List.of();
        return actions.getOrDefault(click.toLowerCase(java.util.Locale.ROOT), List.of());
    }
}
