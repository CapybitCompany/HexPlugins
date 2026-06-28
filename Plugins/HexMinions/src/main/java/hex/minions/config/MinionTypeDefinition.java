package hex.minions.config;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

public record MinionTypeDefinition(
        String id,
        boolean enabled,
        String displayName,
        String category,
        ItemSpec itemSpec,
        String itemDisplayName,
        List<String> itemLore,
        int footprintRadiusBlocks,
        boolean requireSolidGround,
        List<Material> blockedMaterials,
        String appearanceId,
        String menuId,
        List<ResourceDrop> resourceTable,
        String dropSelectionMode,
        Map<Integer, TierDefinition> tiers,
        int maxTier,
        List<String> wikiSpecialItems,
        List<Integer> supportedBoosterTiers,
        AutoSmelterDefinition autoSmelter
) {
    public TierDefinition tier(int tier) {
        return tiers.getOrDefault(tier, tiers.get(1));
    }

    public Material itemMaterial() {
        return itemSpec() == null || itemSpec().material() == null ? Material.PLAYER_HEAD : itemSpec().material();
    }

    public int itemCustomModelData() {
        return itemSpec() == null ? 0 : itemSpec().customModelData();
    }

    public String itemHeadMaterial() {
        if (itemSpec() == null || itemSpec().material() != Material.PLAYER_HEAD) {
            return itemMaterial().name();
        }
        if (!itemSpec().headTextureBase64().isBlank()) {
            return "basehead-" + itemSpec().headTextureBase64();
        }
        if (!itemSpec().headTextureUrl().isBlank()) {
            return "basehead-" + ItemSpec.base64FromUrl(itemSpec().headTextureUrl());
        }
        if (!itemSpec().headOwner().isBlank()) {
            return "head-" + itemSpec().headOwner();
        }
        return "PLAYER_HEAD";
    }
}

