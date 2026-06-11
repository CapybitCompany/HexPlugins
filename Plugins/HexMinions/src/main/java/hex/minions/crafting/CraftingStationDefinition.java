package hex.minions.crafting;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

public record CraftingStationDefinition(String id, boolean enabled, Material block, String specialItemId, String displayName) {
    public static CraftingStationDefinition fromConfig(String id, ConfigurationSection section) {
        Material block = Material.matchMaterial(section.getString("block", "CRAFTING_TABLE"));
        if (block == null) block = Material.CRAFTING_TABLE;
        return new CraftingStationDefinition(id, section.getBoolean("enabled", true), block, section.getString("special-item", id), section.getString("display-name", id));
    }
}
