package hex.minions.config;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

public record MinionTypeDefinition(
        String id,
        boolean enabled,
        String displayName,
        String category,
        Material itemMaterial,
        int itemCustomModelData,
        String itemDisplayName,
        List<String> itemLore,
        int footprintRadiusBlocks,
        boolean requireSolidGround,
        List<Material> blockedMaterials,
        String appearanceId,
        String menuId,
        List<ResourceDrop> resourceTable,
        Map<Integer, TierDefinition> tiers,
        int maxTier
) {
    public TierDefinition tier(int tier) {
        return tiers.getOrDefault(tier, tiers.get(1));
    }
}

