package hexbuildbattle.player;

import java.util.UUID;

public final class PlayerGameData {

    private final UUID playerId;
    private PlayerStatus status;

    public PlayerGameData(UUID playerId, PlayerStatus status) {
        this.playerId = playerId;
        this.status = status;
    }

    public UUID playerId() {
        return playerId;
    }

    public PlayerStatus status() {
        return status;
    }

    public void status(PlayerStatus status) {
        this.status = status;
    }
}
