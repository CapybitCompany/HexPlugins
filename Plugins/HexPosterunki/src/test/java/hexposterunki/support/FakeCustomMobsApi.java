package hexposterunki.support;

import hexcustommobs.api.CustomMobsApi;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Stand-in for HexCustomMobs that spawns plain zombies and tags them with the mob id, mirroring
 * what the real service does. {@link #failNextSpawns} lets a test reproduce a spawn failure.
 */
public final class FakeCustomMobsApi implements CustomMobsApi {

    private final Set<String> mobIds = new LinkedHashSet<>(Set.of("forest_zombie", "desert_husk"));
    private final NamespacedKey key;
    private boolean failNextSpawns;

    public FakeCustomMobsApi(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "custom_mob_id");
    }

    public void failNextSpawns(boolean value) {
        this.failNextSpawns = value;
    }

    @Override
    public String apiVersion() {
        return API_VERSION;
    }

    @Override
    public boolean hasMob(String mobId) {
        return mobId != null && mobIds.contains(mobId.toLowerCase(Locale.ROOT));
    }

    @Override
    public Set<String> mobIds() {
        return Set.copyOf(mobIds);
    }

    @Override
    public Optional<LivingEntity> spawn(Location location, String mobId) {
        if (failNextSpawns || location == null || location.getWorld() == null || !hasMob(mobId)) {
            return Optional.empty();
        }
        Zombie zombie = location.getWorld().spawn(location, Zombie.class);
        zombie.getPersistentDataContainer().set(key, PersistentDataType.STRING,
                mobId.toLowerCase(Locale.ROOT));
        return Optional.of(zombie);
    }

    @Override
    public Optional<String> mobIdOf(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entity.getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }
}
