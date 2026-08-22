package hexnpc.guide.model;

import org.bukkit.Material;

import java.util.Objects;

public record GuideBackground(Material material, boolean hideTooltip) {
    public GuideBackground {
        material = Objects.requireNonNull(material, "material");
    }

    public static GuideBackground defaults() {
        return new GuideBackground(Material.BLACK_STAINED_GLASS_PANE, true);
    }
}
