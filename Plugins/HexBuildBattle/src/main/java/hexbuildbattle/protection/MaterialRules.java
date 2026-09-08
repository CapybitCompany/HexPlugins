package hexbuildbattle.protection;

import hexbuildbattle.config.ConfigService;
import org.bukkit.Material;

import java.util.Set;

public final class MaterialRules {

    private final ConfigService configService;

    public MaterialRules(ConfigService configService) {
        this.configService = configService;
    }

    public boolean isBlockedItem(Material material) {
        if (material == null || material.isAir()) {
            return false;
        }
        if (configService.config().materials().blockedMaterials().contains(material)) {
            return true;
        }

        String name = material.name();
        return name.endsWith("_SPAWN_EGG")
                || name.endsWith("_BOAT")
                || name.endsWith("_CHEST_BOAT")
                || name.endsWith("_MINECART")
                || name.endsWith("_POTION")
                || name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS")
                || name.endsWith("_HORSE_ARMOR")
                || name.endsWith("_DISC")
                || name.startsWith("MUSIC_DISC_")
                || Set.of(
                "TNT",
                "END_CRYSTAL",
                "DRAGON_EGG",
                "COMMAND_BLOCK",
                "CHAIN_COMMAND_BLOCK",
                "REPEATING_COMMAND_BLOCK",
                "STRUCTURE_BLOCK",
                "JIGSAW",
                "ARMOR_STAND",
                "ENDER_PEARL",
                "EGG",
                "SNOWBALL",
                "BOW",
                "CROSSBOW",
                "SPLASH_POTION",
                "LINGERING_POTION",
                "ELYTRA",
                "WRITABLE_BOOK",
                "WRITTEN_BOOK"
        ).contains(name);
    }

    public boolean isBlockedInteraction(Material material) {
        if (material == null || material.isAir()) {
            return false;
        }
        if (configService.config().materials().blockedInteractions().contains(material)) {
            return true;
        }

        String name = material.name();
        return name.endsWith("_DOOR")
                || name.endsWith("_TRAPDOOR")
                || name.endsWith("_FENCE_GATE")
                || name.endsWith("_BUTTON")
                || name.endsWith("_BED")
                || name.endsWith("_CAULDRON")
                || name.endsWith("_SIGN")
                || name.endsWith("_HANGING_SIGN")
                || name.endsWith("_SHULKER_BOX")
                || Set.of(
                "LEVER",
                "CHEST",
                "TRAPPED_CHEST",
                "ENDER_CHEST",
                "BARREL",
                "FURNACE",
                "BLAST_FURNACE",
                "SMOKER",
                "ANVIL",
                "CHIPPED_ANVIL",
                "DAMAGED_ANVIL",
                "ENCHANTING_TABLE",
                "HOPPER",
                "DROPPER",
                "DISPENSER",
                "BREWING_STAND",
                "CRAFTER",
                "CRAFTING_TABLE",
                "CARTOGRAPHY_TABLE",
                "SMITHING_TABLE",
                "FLETCHING_TABLE",
                "GRINDSTONE",
                "LOOM",
                "STONECUTTER",
                "BEACON",
                "BELL",
                "CHISELED_BOOKSHELF",
                "COMPOSTER",
                "DECORATED_POT",
                "JUKEBOX",
                "LECTERN",
                "NOTE_BLOCK",
                "RESPAWN_ANCHOR",
                "CAMPFIRE",
                "SOUL_CAMPFIRE",
                "CAULDRON",
                "REPEATER",
                "COMPARATOR",
                "DAYLIGHT_DETECTOR",
                "CAKE",
                "BEEHIVE",
                "BEE_NEST"
        ).contains(name);
    }

    public boolean isAllowedUtilityItem(Material material) {
        return material == Material.WATER_BUCKET
                || material == Material.LAVA_BUCKET
                || material == Material.FLINT_AND_STEEL;
    }
}
