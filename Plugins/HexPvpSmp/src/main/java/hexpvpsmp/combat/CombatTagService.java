package hexpvpsmp.combat;

import hexpvpsmp.config.HexPvpConfig;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Combat tag bookkeeping. In-memory only — restarts clear tags.
 * Single global expiry sweep happens inside {@link hexpvpsmp.ui.ActionBarService}'s
 * 10-tick task; we never schedule one task per player.
 */
public final class CombatTagService {

    private final Server server;
    private final Supplier<HexPvpConfig> configSupplier;
    private final Map<UUID, CombatState> tags = new HashMap<>();

    public CombatTagService(Server server, Supplier<HexPvpConfig> configSupplier) {
        this.server = Objects.requireNonNull(server, "server");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    /** Tag a player or refresh an existing tag. Returns the updated state. */
    public CombatState tag(Player player) {
        Objects.requireNonNull(player, "player");
        long now = currentTick();
        int durationTicks = configSupplier.get().combat().durationTicks();
        long expiry = now + durationTicks;
        CombatState state = tags.get(player.getUniqueId());
        if (state == null) {
            state = new CombatState(expiry);
            tags.put(player.getUniqueId(), state);
        } else {
            state.refreshExpiry(expiry);
        }
        return state;
    }

    public boolean isTagged(Player player) {
        return player != null && isTagged(player.getUniqueId());
    }

    public boolean isTagged(UUID playerId) {
        CombatState state = tags.get(playerId);
        if (state == null) {
            return false;
        }
        if (currentTick() >= state.expiryTick()) {
            tags.remove(playerId);
            return false;
        }
        return true;
    }

    public Optional<CombatState> state(UUID playerId) {
        return Optional.ofNullable(tags.get(playerId));
    }

    /** Remove tag and return whether something was removed. */
    public boolean untag(UUID playerId) {
        return tags.remove(playerId) != null;
    }

    public int remainingSeconds(UUID playerId) {
        CombatState state = tags.get(playerId);
        if (state == null) {
            return 0;
        }
        long now = currentTick();
        if (now >= state.expiryTick()) {
            return 0;
        }
        return (int) Math.ceil((state.expiryTick() - now) / 20.0D);
    }

    public Map<UUID, CombatState> snapshot() {
        return Map.copyOf(tags);
    }

    /** Updates last-safe-location only if the player is tagged. */
    public void updateLastSafeLocation(Player player, Location location) {
        if (player == null || location == null) {
            return;
        }
        CombatState state = tags.get(player.getUniqueId());
        if (state != null) {
            state.setLastSafeLocation(location.clone());
        }
    }

    public void clearAll() {
        tags.clear();
    }

    /** Drops tags whose expiry has elapsed. Called from the actionbar sweep. */
    public void expire(long now) {
        tags.entrySet().removeIf(e -> now >= e.getValue().expiryTick());
    }

    private long currentTick() {
        return server.getCurrentTick();
    }
}
