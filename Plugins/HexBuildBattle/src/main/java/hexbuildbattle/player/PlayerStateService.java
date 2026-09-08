package hexbuildbattle.player;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PlayerStateService {

    private final Map<UUID, PlayerGameData> players = new HashMap<>();

    public void mark(UUID playerId, PlayerStatus status) {
        players.compute(playerId, (id, existing) -> {
            if (existing == null) {
                return new PlayerGameData(id, status);
            }
            existing.status(status);
            return existing;
        });
    }

    public void remove(UUID playerId) {
        players.remove(playerId);
    }

    public Optional<PlayerGameData> data(UUID playerId) {
        return Optional.ofNullable(players.get(playerId));
    }

    public PlayerStatus status(UUID playerId) {
        return data(playerId).map(PlayerGameData::status).orElse(PlayerStatus.LOBBY);
    }

    public boolean isWaitingNext(UUID playerId) {
        return status(playerId) == PlayerStatus.WAITING_NEXT;
    }

    public void clear() {
        players.clear();
    }
}
