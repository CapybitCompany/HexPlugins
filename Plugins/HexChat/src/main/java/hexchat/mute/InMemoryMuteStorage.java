package hexchat.mute;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Przechowywanie w pamięci — używane w testach oraz jako fallback, gdy zapis na dysk
 * jest niedostępny.
 */
public final class InMemoryMuteStorage implements MuteStorage {

    private final Map<UUID, MuteEntry> entries = new ConcurrentHashMap<>();

    @Override
    public Map<UUID, MuteEntry> loadAll() {
        return new HashMap<>(entries);
    }

    @Override
    public void save(MuteEntry entry) {
        entries.put(entry.playerId(), entry);
    }

    @Override
    public void remove(UUID playerId) {
        entries.remove(playerId);
    }
}
