package hexnpc.guide.model;

import org.bukkit.Material;

import java.util.List;
import java.util.Objects;

public record GuideIcon(Material material, Integer customModelData, String name, List<String> lore) {
    public GuideIcon {
        material = Objects.requireNonNull(material, "material");
        name = name == null ? "" : name;
        lore = lore == null ? List.of() : List.copyOf(lore);
    }
}
