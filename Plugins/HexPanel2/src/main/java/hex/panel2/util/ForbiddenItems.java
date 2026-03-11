package hex.panel2.util;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Set;

public final class ForbiddenItems {

    private static final Set<Material> FORBIDDEN_EXACT = EnumSet.of(
            Material.DRAGON_EGG,
            Material.TNT,
            Material.SPAWNER,
            Material.ENDER_CHEST,
            Material.WITHER_SKELETON_SKULL,
            Material.END_CRYSTAL,
            Material.FLINT_AND_STEEL,
            Material.ENDER_PEARL,
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
                || name.endsWith("_BED");
    }
}
