package hex.panel2.util;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Set;

public final class ForbiddenItems {

    private static final Set<Material> FORBIDDEN_EXACT = EnumSet.of(
            Material.BEDROCK,
            Material.RED_CONCRETE,
            Material.DRAGON_EGG,
            Material.TNT,
            Material.SPAWNER,
            Material.TRIAL_SPAWNER,
            Material.ENDER_CHEST,
            Material.WITHER_SKELETON_SKULL,
            Material.END_CRYSTAL,
            Material.FLINT_AND_STEEL,
            Material.ENDER_PEARL,
            Material.WIND_CHARGE,
            Material.FIRE_CHARGE,
            Material.EXPERIENCE_BOTTLE,
            Material.OMINOUS_BOTTLE,
            Material.MINECART,
            Material.RESPAWN_ANCHOR,
            Material.LAVA_BUCKET,
            Material.WATER_BUCKET
    );

    private ForbiddenItems() {
    }

    public static boolean isForbidden(Material material) {
        if (material == null) {
            return false;
        }
        if (FORBIDDEN_EXACT.contains(material)) {
            return true;
        }

        String name = material.name();
        return name.endsWith("_SPAWN_EGG")
                || name.endsWith("_SHULKER_BOX")
                || name.endsWith("_BED")
                || name.endsWith("_BOAT")
                || name.endsWith("_CHEST_BOAT")
                || name.endsWith("_RAFT")
                || name.endsWith("_CHEST_RAFT")
                || name.endsWith("_MINECART");
    }
}
