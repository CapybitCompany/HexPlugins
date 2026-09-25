package hexposterunki.boss;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Isolated bridge to an external boss plugin. HexPosterunki owns the scheduling and the outpost
 * location; the boss plugin owns the fight itself and all boss rewards.
 */
public interface BossEngineAdapter {

    String providerId();

    AdapterHealth health();

    /** All boss ids the engine knows; used to reject unknown configured ids at startup. */
    Set<String> bossIds();

    /** Validates a single configured boss id without spawning anything. */
    AdapterHealth validateBoss(String bossId);

    /** Spawns the boss at the dynamic outpost boss position. */
    BossSpawnResult spawn(String bossId, Location location);

    /** Removes a boss entity that belongs to us (reset / wipe / admin stop). */
    StopBossResult stop(UUID bossEntityId);

    boolean isBoss(Entity entity);

    Optional<String> bossId(Entity entity);
}
