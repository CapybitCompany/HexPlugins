package hexposterunki.boss;

import java.util.UUID;

public record BossSpawnResult(boolean success, UUID entityId, String message) {

    public static BossSpawnResult ok(UUID entityId) {
        return new BossSpawnResult(true, entityId, "OK");
    }

    public static BossSpawnResult fail(String message) {
        return new BossSpawnResult(false, null, message);
    }
}
