package hexposterunki.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tracks final kills on run-tagged custom mobs. Bukkit-free on purpose: death, region exit,
 * logout, world change and teleport-out all funnel into {@link #reset(UUID)}.
 */
public final class KillLedger {

    private final Map<UUID, KillEntry> entries = new LinkedHashMap<>();

    /** Registers one final kill and returns the resulting entry. */
    public KillEntry recordKill(UUID playerId, UUID townId, long nowMillis) {
        KillEntry current = entries.get(playerId);
        KillEntry updated = current == null
                ? new KillEntry(playerId, townId, 1, nowMillis)
                : current.withKill(townId, nowMillis);
        entries.put(playerId, updated);
        return updated;
    }

    /**
     * Drops the entire progress of one player.
     *
     * @return true when there actually was progress to lose (so the caller can stay quiet otherwise).
     */
    public boolean reset(UUID playerId) {
        KillEntry removed = entries.remove(playerId);
        return removed != null && removed.kills() > 0;
    }

    public int kills(UUID playerId) {
        KillEntry entry = entries.get(playerId);
        return entry == null ? 0 : entry.kills();
    }

    public Optional<KillEntry> entry(UUID playerId) {
        return Optional.ofNullable(entries.get(playerId));
    }

    public Collection<KillEntry> entries() {
        return List.copyOf(entries.values());
    }

    public void clear() {
        entries.clear();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Replaces the whole ledger, used by startup recovery. */
    public void load(Collection<KillEntry> loaded) {
        entries.clear();
        for (KillEntry entry : new ArrayList<>(loaded)) {
            if (entry != null) {
                entries.put(entry.playerId(), entry);
            }
        }
    }
}
