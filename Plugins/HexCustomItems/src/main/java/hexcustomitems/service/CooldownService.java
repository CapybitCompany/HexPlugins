package hexcustomitems.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Hält Item-Cooldowns rein im Speicher (kein Event, kein Scheduler).
 * Ablauf wird lazy beim Nachfragen geprüft - SMP-freundlich und ohne Hintergrundlast.
 * Über {@link #snapshot()} / {@link #load(Map)} optional persistierbar.
 *
 * <p>Die Zeitquelle ({@link LongSupplier} in Millisekunden) ist injizierbar, damit
 * Tests ohne echte Wartezeiten arbeiten können.
 */
public final class CooldownService {

    private final Map<UUID, Map<String, Long>> expiryByPlayer = new ConcurrentHashMap<>();
    private final LongSupplier clockMillis;

    public CooldownService() {
        this(System::currentTimeMillis);
    }

    public CooldownService(LongSupplier clockMillis) {
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** Verbleibende Cooldown-Sekunden (aufgerundet), oder 0 wenn frei. */
    public long remainingSeconds(UUID playerId, String itemId) {
        Map<String, Long> perItem = expiryByPlayer.get(playerId);
        if (perItem == null) {
            return 0L;
        }
        Long expiry = perItem.get(itemId);
        if (expiry == null) {
            return 0L;
        }
        long diff = expiry - clockMillis.getAsLong();
        if (diff <= 0L) {
            perItem.remove(itemId);
            return 0L;
        }
        return (diff + 999L) / 1000L;
    }

    /** Setzt einen Cooldown von cooldownSeconds; 0 oder weniger wird ignoriert. */
    public void apply(UUID playerId, String itemId, int cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return;
        }
        long expiry = clockMillis.getAsLong() + cooldownSeconds * 1000L;
        expiryByPlayer
                .computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(itemId, expiry);
    }

    /** Entfernt alle Cooldowns eines Spielers (z.B. beim Verlassen ohne Persistenz). */
    public void clear(UUID playerId) {
        expiryByPlayer.remove(playerId);
    }

    /** Momentaufnahme aller noch nicht abgelaufenen Cooldowns (für Persistenz). */
    public Map<UUID, Map<String, Long>> snapshot() {
        long now = clockMillis.getAsLong();
        Map<UUID, Map<String, Long>> copy = new HashMap<>();
        for (Map.Entry<UUID, Map<String, Long>> playerEntry : expiryByPlayer.entrySet()) {
            Map<String, Long> perItem = new HashMap<>();
            for (Map.Entry<String, Long> itemEntry : playerEntry.getValue().entrySet()) {
                if (itemEntry.getValue() > now) {
                    perItem.put(itemEntry.getKey(), itemEntry.getValue());
                }
            }
            if (!perItem.isEmpty()) {
                copy.put(playerEntry.getKey(), perItem);
            }
        }
        return copy;
    }

    /** Lädt persistierte Cooldowns; bereits abgelaufene Einträge werden ignoriert. */
    public void load(Map<UUID, Map<String, Long>> data) {
        long now = clockMillis.getAsLong();
        for (Map.Entry<UUID, Map<String, Long>> playerEntry : data.entrySet()) {
            for (Map.Entry<String, Long> itemEntry : playerEntry.getValue().entrySet()) {
                if (itemEntry.getValue() > now) {
                    expiryByPlayer
                            .computeIfAbsent(playerEntry.getKey(), ignored -> new ConcurrentHashMap<>())
                            .put(itemEntry.getKey(), itemEntry.getValue());
                }
            }
        }
    }
}
