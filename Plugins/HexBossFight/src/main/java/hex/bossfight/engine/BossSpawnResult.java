package hex.bossfight.engine;

import java.util.UUID;

public record BossSpawnResult(boolean success, UUID entityId, String message) {
    public static BossSpawnResult ok(UUID id){ return new BossSpawnResult(true,id,"OK"); }
    public static BossSpawnResult fail(String message){ return new BossSpawnResult(false,null,message); }
}
