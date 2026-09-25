package hexposterunki.support;

import hexposterunki.boss.AdapterHealth;
import hexposterunki.boss.BossEngineAdapter;
import hexposterunki.boss.BossSpawnResult;
import hexposterunki.boss.StopBossResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Test double for HexPosterunki's own {@link BossEngineAdapter} boundary. It does not imitate any
 * STORMBOSSY class or method; it only stands in for "some boss engine" so the engine's boss phase can
 * run on a mock server.
 *
 * <p>{@link #killOnStop(boolean)} reproduces a boss engine whose stop call kills the entity and
 * therefore fires a real {@link EntityDeathEvent} while the outpost is being reset.
 */
public final class FakeBossAdapter implements BossEngineAdapter {

    private boolean killOnStop;
    private int spawns;

    public void killOnStop(boolean value) {
        this.killOnStop = value;
    }

    public int spawns() {
        return spawns;
    }

    @Override
    public String providerId() {
        return "test";
    }

    @Override
    public AdapterHealth health() {
        return AdapterHealth.ok();
    }

    @Override
    public Set<String> bossIds() {
        return Set.of("wladca_burzy");
    }

    @Override
    public AdapterHealth validateBoss(String bossId) {
        return bossIds().contains(bossId) ? AdapterHealth.ok() : AdapterHealth.unavailable("nieznany boss");
    }

    @Override
    public BossSpawnResult spawn(String bossId, Location location) {
        if (location.getWorld() == null) {
            return BossSpawnResult.fail("brak świata");
        }
        spawns++;
        Zombie boss = location.getWorld().spawn(location, Zombie.class);
        return BossSpawnResult.ok(boss.getUniqueId());
    }

    @Override
    public StopBossResult stop(UUID bossEntityId) {
        Entity entity = Bukkit.getEntity(bossEntityId);
        if (entity == null) {
            return StopBossResult.fail("brak encji");
        }
        if (killOnStop && entity instanceof LivingEntity living) {
            Bukkit.getPluginManager().callEvent(new EntityDeathEvent(living,
                    DamageSource.builder(DamageType.GENERIC_KILL).build(), List.of()));
        }
        entity.remove();
        return StopBossResult.ok();
    }

    @Override
    public boolean isBoss(Entity entity) {
        return false;
    }

    @Override
    public Optional<String> bossId(Entity entity) {
        return Optional.empty();
    }
}
