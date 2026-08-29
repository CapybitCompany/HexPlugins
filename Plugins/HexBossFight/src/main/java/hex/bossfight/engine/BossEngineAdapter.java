package hex.bossfight.engine;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface BossEngineAdapter {
    String providerId();
    AdapterHealth health();
    Set<String> bossIds();
    AdapterHealth validateBoss(String bossId, int spawnLocation, boolean strictSchedules, boolean strictRewards);
    Optional<Location> spawnLocation(String bossId, int spawnLocation);
    BossSpawnResult spawn(String bossId, int spawnLocation);
    StopBossResult stop(UUID bossEntityId);
    boolean isBoss(Entity entity);
    Optional<String> bossId(Entity entity);
}
