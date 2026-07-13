package hexpvpsmp.region;

/**
 * Kind of protected region served by this plugin.
 *
 * <ul>
 *   <li>{@link #SPAWN_SAFEZONE}: no PvP, no building, blocks combat-tagged
 *       entry, blocks mob spawns. The spawn bubble.</li>
 *   <li>{@link #NO_BUILD}: PvP allowed, combat allowed, but building/breaking
 *       is denied. Keeps the area in front of spawn clean without turning it
 *       into a PvP safe haven.</li>
 * </ul>
 */
public enum RegionType {
    SPAWN_SAFEZONE,
    NO_BUILD;

    /** Every region type denies building; only the safezone denies PvP. */
    public boolean blocksBuild() {
        return true;
    }

    public boolean blocksPvp() {
        return this == SPAWN_SAFEZONE;
    }
}
