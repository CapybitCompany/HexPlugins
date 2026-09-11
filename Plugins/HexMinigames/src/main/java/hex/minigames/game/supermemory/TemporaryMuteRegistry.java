package hex.minigames.game.supermemory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TemporaryMuteRegistry {
    private final Map<UUID, String> mutedPlayers = new HashMap<>();

    boolean markMuted(UUID playerId, String playerName) {
        if (playerId == null || playerName == null || playerName.isBlank()) return false;
        if (mutedPlayers.containsKey(playerId)) return false;
        mutedPlayers.put(playerId, playerName);
        return true;
    }

    String remove(UUID playerId) {
        return mutedPlayers.remove(playerId);
    }

    List<UUID> playerIds() {
        return List.copyOf(mutedPlayers.keySet());
    }

    List<String> clear() {
        List<String> names = new ArrayList<>(mutedPlayers.values());
        mutedPlayers.clear();
        return names;
    }

    boolean contains(UUID playerId) {
        return mutedPlayers.containsKey(playerId);
    }
}
