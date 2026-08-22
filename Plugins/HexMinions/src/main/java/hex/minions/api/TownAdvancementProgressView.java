package hex.minions.api;

import org.bukkit.Material;

/**
 * Read-only town progression data intended for external guide/UI plugins.
 * The actual requirement/reward logic remains owned by HexMinions.
 */
public record TownAdvancementProgressView(
        String id,
        String title,
        String description,
        Material icon,
        int growthPoints,
        boolean completed,
        long current,
        long required,
        String category
) {
    public TownAdvancementProgressView {
        if (id == null) id = "";
        if (title == null) title = "";
        if (description == null) description = "";
        if (icon == null) icon = Material.BOOK;
        if (category == null || category.isBlank()) category = "OTHER";
        growthPoints = Math.max(0, growthPoints);
        current = Math.max(0L, current);
        required = Math.max(1L, required);
    }
}
