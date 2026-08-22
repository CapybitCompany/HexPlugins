package hexnpc.workflow.model;

import org.bukkit.Material;

public record WorkflowMenuBackground(Material material, boolean hideTooltip) {
    public WorkflowMenuBackground {
        material = material == null ? Material.BLACK_STAINED_GLASS_PANE : material;
    }
    public static WorkflowMenuBackground defaults() {
        return new WorkflowMenuBackground(Material.BLACK_STAINED_GLASS_PANE, true);
    }
}
