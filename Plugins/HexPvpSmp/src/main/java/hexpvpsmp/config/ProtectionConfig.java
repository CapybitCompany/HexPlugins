package hexpvpsmp.config;

/**
 * Toggles for the data-driven block/interaction/item protection in spawn and
 * no-build regions. Kept intentionally small: the concrete material rules live
 * in {@code InteractionRules} (data-driven Material sets / Bukkit tags), this
 * record only carries the admin-facing switches.
 *
 * <ul>
 *   <li>{@code bypassBuild/Interact/Items}: whether {@code hexpvpsmp.bypass} /
 *       OP players are exempt from the build, interaction and item rules. Set a
 *       switch to {@code false} to enforce that rule even for OP.</li>
 *   <li>{@code blockButtons}: whether buttons are treated as protected
 *       interactables (they are the one interactable an admin may want to keep
 *       usable, e.g. for a spawn door button).</li>
 *   <li>{@code blockPvpItemsInNoBuild}: whether combat/mobility items (pearls,
 *       snowballs, bows, ...) are also blocked inside NO_BUILD zones. Terrain
 *       items are always blocked in every protected region; this only governs
 *       the PvP-relevant ones. Spawn safezones always block everything.</li>
 * </ul>
 */
public record ProtectionConfig(
        boolean bypassBuild,
        boolean bypassInteract,
        boolean bypassItems,
        boolean blockButtons,
        boolean blockPvpItemsInNoBuild
) {
    public static ProtectionConfig defaults() {
        return new ProtectionConfig(true, true, true, false, false);
    }
}
