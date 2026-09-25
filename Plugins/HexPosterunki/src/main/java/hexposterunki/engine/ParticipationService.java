package hexposterunki.engine;

import hexposterunki.domain.KillEntry;
import hexposterunki.domain.KillLedger;
import hexposterunki.domain.Presence;
import hexposterunki.towns.TownsAdapter;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks who is inside the active outpost region, when they entered, and how many run kills they
 * hold. The pure decisions live in {@link hexposterunki.domain.ControlResolver} and
 * {@link hexposterunki.domain.RankingCalculator}; this class only supplies the Bukkit facts.
 */
public final class ParticipationService {

    private TownsAdapter towns;
    private final KillLedger ledger = new KillLedger();
    private final Map<UUID, Long> insideSince = new LinkedHashMap<>();

    public ParticipationService(TownsAdapter towns) {
        this.towns = Objects.requireNonNull(towns, "towns");
    }

    public KillLedger ledger() {
        return ledger;
    }

    /** Swaps the towns binding after a reload; presence and kill standings stay untouched. */
    public void rebindTowns(TownsAdapter replacement) {
        this.towns = Objects.requireNonNull(replacement, "replacement");
    }

    /** @return true when the player was not registered as inside before. */
    public boolean markInside(UUID playerId, long nowMillis) {
        return insideSince.putIfAbsent(playerId, nowMillis) == null;
    }

    /** @return true when the player actually was registered as inside. */
    public boolean markOutside(UUID playerId) {
        return insideSince.remove(playerId) != null;
    }

    public boolean isInside(UUID playerId) {
        return insideSince.containsKey(playerId);
    }

    public Set<UUID> insideIds() {
        return Set.copyOf(insideSince.keySet());
    }

    public Optional<Long> enteredAt(UUID playerId) {
        return Optional.ofNullable(insideSince.get(playerId));
    }

    /**
     * Eligible players currently inside: online, alive, not spectating, and members of a town.
     *
     * <p>Only offline players are dropped from the tracked set here. Dead or spectating players
     * stay tracked but are excluded from the result - removing them would make the engine's
     * presence sweep re-add them on the very next tick.
     */
    public List<Presence> presences() {
        List<Presence> result = new ArrayList<>();
        Set<UUID> offline = new LinkedHashSet<>();
        for (Map.Entry<UUID, Long> entry : insideSince.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                offline.add(entry.getKey());
                continue;
            }
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            Optional<UUID> townId = towns.townIdOf(entry.getKey());
            if (townId.isEmpty()) {
                // Townless players may stand there, but they never control the outpost.
                continue;
            }
            result.add(new Presence(entry.getKey(), townId.get(), entry.getValue()));
        }
        offline.forEach(insideSince::remove);
        return result;
    }

    /** Online players inside the region, regardless of town, for actionbar updates. */
    public List<Player> onlineInside() {
        List<Player> players = new ArrayList<>();
        for (UUID id : insideSince.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    public boolean isMemberOf(UUID playerId, UUID townId) {
        if (playerId == null || townId == null) {
            return false;
        }
        return towns.townIdOf(playerId).map(townId::equals).orElse(false);
    }

    public Optional<UUID> townOf(UUID playerId) {
        return towns.townIdOf(playerId);
    }

    public KillEntry recordKill(UUID playerId, long nowMillis) {
        UUID townId = towns.townIdOf(playerId).orElse(null);
        return ledger.recordKill(playerId, townId, nowMillis);
    }

    public boolean resetProgress(UUID playerId) {
        return ledger.reset(playerId);
    }

    /** Full wipe of presence and kills; used on reset, wipe and completion. */
    public void clear() {
        insideSince.clear();
        ledger.clear();
    }

    public void clearPresence() {
        insideSince.clear();
    }
}
