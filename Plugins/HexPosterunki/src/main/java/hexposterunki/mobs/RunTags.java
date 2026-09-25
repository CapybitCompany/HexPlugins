package hexposterunki.mobs;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;

/**
 * PDC tags stamped on everything HexPosterunki creates.
 *
 * <p>Only entities carrying these tags count for wave progress and kills, so a wandering
 * HexCustomMobs mob that happens to walk into the region never interferes with a run.
 */
public final class RunTags {

    private final NamespacedKey runKey;
    private final NamespacedKey outpostKey;
    private final NamespacedKey waveKey;
    private final NamespacedKey bossKey;
    private final NamespacedKey displayKey;

    public RunTags(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        this.runKey = new NamespacedKey(plugin, "run_id");
        this.outpostKey = new NamespacedKey(plugin, "outpost_id");
        this.waveKey = new NamespacedKey(plugin, "wave");
        this.bossKey = new NamespacedKey(plugin, "boss");
        this.displayKey = new NamespacedKey(plugin, "display");
    }

    public void tagMob(Entity entity, String runId, String outpostId, int wave) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(runKey, PersistentDataType.STRING, runId);
        data.set(outpostKey, PersistentDataType.STRING, outpostId);
        data.set(waveKey, PersistentDataType.INTEGER, wave);
    }

    public void tagBoss(Entity entity, String runId, String outpostId) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(runKey, PersistentDataType.STRING, runId);
        data.set(outpostKey, PersistentDataType.STRING, outpostId);
        data.set(waveKey, PersistentDataType.INTEGER, 0);
        data.set(bossKey, PersistentDataType.BYTE, (byte) 1);
    }

    public void tagDisplay(Entity entity, String runId, String outpostId) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(runKey, PersistentDataType.STRING, runId);
        data.set(outpostKey, PersistentDataType.STRING, outpostId);
        data.set(displayKey, PersistentDataType.BYTE, (byte) 1);
    }

    public Optional<String> runIdOf(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entity.getPersistentDataContainer().get(runKey, PersistentDataType.STRING));
    }

    public Optional<String> outpostIdOf(Entity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entity.getPersistentDataContainer().get(outpostKey, PersistentDataType.STRING));
    }

    public int waveOf(Entity entity) {
        if (entity == null) {
            return 0;
        }
        Integer wave = entity.getPersistentDataContainer().get(waveKey, PersistentDataType.INTEGER);
        return wave == null ? 0 : wave;
    }

    public boolean isBoss(Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE);
    }

    public boolean isDisplay(Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(displayKey, PersistentDataType.BYTE);
    }

    /** True for any entity this plugin created, regardless of run. */
    public boolean isTagged(Entity entity) {
        return runIdOf(entity).isPresent();
    }

    /** True for an encounter entity (mob or boss) of exactly this run. */
    public boolean belongsTo(Entity entity, String runId) {
        return runId != null && runIdOf(entity).map(runId::equals).orElse(false);
    }
}
