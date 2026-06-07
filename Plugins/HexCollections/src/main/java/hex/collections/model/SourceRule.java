package hex.collections.model;

import hex.collections.api.CollectionSource;
import org.bukkit.Material;

import java.util.Set;

public record SourceRule(
        CollectionSource source,
        Set<Material> allowedMaterials,
        Set<String> allowedWorlds,
        Set<String> blockedWorlds,
        boolean allowInTownClaims,
        boolean denyPlayerPlacedBlocks,
        boolean denyRecentlyBrokenBlocks
) {
    public boolean materialAllowed(Material material) {
        return allowedMaterials.isEmpty() || allowedMaterials.contains(material);
    }

    public boolean worldAllowed(String world) {
        if (world == null) return false;
        if (!allowedWorlds.isEmpty() && !allowedWorlds.contains(world)) return false;
        return !blockedWorlds.contains(world);
    }
}

