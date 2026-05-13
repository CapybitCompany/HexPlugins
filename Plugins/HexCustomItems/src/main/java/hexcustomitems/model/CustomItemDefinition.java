package hexcustomitems.model;

import org.bukkit.Material;

import java.util.List;
import java.util.Objects;

public record CustomItemDefinition(
        String id,
        Material material,
        String name,
        List<String> lore,
        boolean dropProtection,
        ItemEffectSettings effect
) {
    public CustomItemDefinition {
        id = Objects.requireNonNull(id, "id");
        material = Objects.requireNonNull(material, "material");
        name = Objects.requireNonNull(name, "name");
        lore = List.copyOf(Objects.requireNonNull(lore, "lore"));
        effect = Objects.requireNonNull(effect, "effect");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id cannot be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
    }
}
