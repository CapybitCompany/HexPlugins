package hexpvpsmp.protection;

import org.bukkit.Material;
import org.bukkit.Tag;

/**
 * Data-driven catalogue of what may not be used/placed inside protected
 * regions. Everything here is expressed as Material sets / Bukkit tags and
 * category predicates rather than per-call {@code if} chains in the listener,
 * so the rules stay readable and easy to extend.
 *
 * <p>Three orthogonal questions are answered:
 * <ul>
 *   <li>{@link #isProtectedInteractable(Material, boolean)} — right-clickable
 *       blocks (gates, doors, levers, ...) that must not be operated in any
 *       protected region.</li>
 *   <li>{@link #isBlockedContainer(Material)} — containers that must stay
 *       closed (everything but a crafting table; public chests are handled by
 *       the registry, not here).</li>
 *   <li>{@link #itemCategory(Material)} — held items whose use is restricted:
 *       {@code TERRAIN} items are blocked in every protected region,
 *       {@code COMBAT} items only in spawn (and optionally in no-build).</li>
 * </ul>
 */
public final class InteractionRules {

    /** How strongly a held item's use is restricted inside protected regions. */
    public enum ItemCategory {
        /** Never restricted. */
        NONE,
        /** World/entity-modifying (boats, minecarts, bone meal, eggs, ...). Blocked in every protected region. */
        TERRAIN,
        /** PvP / mobility (pearls, snowballs, potions, ...). Blocked in spawn; in no-build only if configured. */
        COMBAT
    }

    private InteractionRules() {
    }

    /**
     * True for a block that a normal player must not operate inside a protected
     * region. Crafting tables are intentionally excluded (allowed everywhere).
     *
     * @param blockButtons whether buttons count as protected (config-driven)
     */
    public static boolean isProtectedInteractable(Material material, boolean blockButtons) {
        if (material == null) {
            return false;
        }
        if (Tag.FENCE_GATES.isTagged(material)) return true;
        if (Tag.DOORS.isTagged(material)) return true;
        if (Tag.TRAPDOORS.isTagged(material)) return true;
        if (Tag.CANDLES.isTagged(material)) return true;
        if (Tag.FLOWER_POTS.isTagged(material)) return true;
        if (Tag.ALL_SIGNS.isTagged(material)) return true;
        if (blockButtons && Tag.BUTTONS.isTagged(material)) return true;
        return switch (material) {
            case LEVER,
                 JUKEBOX,
                 STONECUTTER,
                 SWEET_BERRY_BUSH,
                 CAVE_VINES,
                 CAVE_VINES_PLANT -> true;
            default -> false;
        };
    }

    /**
     * True for a container block whose inventory access must be blocked inside a
     * protected region. Crafting tables and ender chests are allowed;
     * public chests are exempted by the caller via the registry.
     */
    public static boolean isBlockedContainer(Material material) {
        if (material == null) {
            return false;
        }
        if (Tag.SHULKER_BOXES.isTagged(material)) return true;
        return switch (material) {
            case CHEST,
                 TRAPPED_CHEST,
                 BARREL,
                 HOPPER,
                 DROPPER,
                 DISPENSER,
                 FURNACE,
                 BLAST_FURNACE,
                 SMOKER,
                 BREWING_STAND,
                 BEACON,
                 CHISELED_BOOKSHELF,
                 DECORATED_POT -> true;
            default -> false;
        };
    }

    /** Classifies a held item for the region-based use restrictions. */
    public static ItemCategory itemCategory(Material material) {
        if (material == null || material == Material.AIR) {
            return ItemCategory.NONE;
        }
        String name = material.name();
        // Boats / minecarts: world-modifying placements.
        if (name.endsWith("_BOAT") || name.endsWith("_CHEST_BOAT") || name.endsWith("_RAFT")) {
            return ItemCategory.TERRAIN;
        }
        if (name.endsWith("MINECART")) {
            return ItemCategory.TERRAIN;
        }
        switch (material) {
            case BONE_MEAL:
            case ENDER_EYE:
            case EGG:            // spawns a chicken -> adds an entity to the region
            case ENDER_PEARL:    // teleport could bypass entry protection
            case GOAT_HORN:      // hard-blocked: never usable in a protected region
                return ItemCategory.TERRAIN;
            case SNOWBALL:
            case WIND_CHARGE:
            case FIREWORK_ROCKET:
            case SPLASH_POTION:
            case LINGERING_POTION:
            case BOW:
            case CROSSBOW:
                return ItemCategory.COMBAT;
            default:
                return ItemCategory.NONE;
        }
    }

    /**
     * Blocks that a normal player may always use even inside a protected region.
     * Checked before container / interactable blocking so explicitly allowed
     * blocks stay usable in spawn and no-build. Public chests are handled
     * separately by the registry.
     */
    public static boolean isAlwaysAllowed(Material material) {
        return material == Material.CRAFTING_TABLE
                || material == Material.ENDER_CHEST;
    }
}
