package hexpvpsmp.combat;

import org.bukkit.Location;

/**
 * Mutable per-player combat state, owned by {@link CombatTagService}.
 * Mutable for cheap updates inside the service; never exposed outside it.
 */
public final class CombatState {

    private long expiryTick;
    private Location lastSafeLocation;
    private long lastWarningTick;

    public CombatState(long expiryTick) {
        this.expiryTick = expiryTick;
        this.lastWarningTick = Long.MIN_VALUE;
    }

    public long expiryTick() {
        return expiryTick;
    }

    public void refreshExpiry(long newExpiry) {
        this.expiryTick = newExpiry;
    }

    public Location lastSafeLocation() {
        return lastSafeLocation;
    }

    public void setLastSafeLocation(Location loc) {
        this.lastSafeLocation = loc;
    }

    public long lastWarningTick() {
        return lastWarningTick;
    }

    public void setLastWarningTick(long tick) {
        this.lastWarningTick = tick;
    }
}
