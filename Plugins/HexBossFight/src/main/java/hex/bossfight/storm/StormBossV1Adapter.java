package hex.bossfight.storm;

import hex.bossfight.engine.*;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Version-pinned reflection bridge for the exact STORMBOSSY 1.0 JAR audited with this project.
 * No class from pl.ZIFFY.* is linked at compile time.
 */
public final class StormBossV1Adapter implements BossEngineAdapter {
    private final Plugin storm;
    private final String supportedVersion;
    private final NamespacedKey bossKey;
    private AdapterHealth health;
    private Object configManager;
    private Object bossManager;
    private Object scheduleManager;
    private Method getBoss;
    private Method listBosses;
    private Method spawnBoss;
    private Method stopBoss;
    private Method managerIsBoss;

    public StormBossV1Adapter(Plugin storm, String supportedVersion) {
        this.storm = storm;
        this.supportedVersion = supportedVersion == null ? "1.0" : supportedVersion;
        this.bossKey = storm == null ? null : new NamespacedKey(storm, "storm_boss_entity");
        this.health = bind();
    }

    private AdapterHealth bind() {
        if (storm == null || !storm.isEnabled()) return new AdapterHealth(AdapterHealth.Status.DEPENDENCY_UNAVAILABLE, "STORMBOSSY is not enabled");
        String version = storm.getDescription().getVersion();
        if (!supportedVersion.equals(version)) return new AdapterHealth(AdapterHealth.Status.UNSUPPORTED_VERSION, "Expected STORMBOSSY " + supportedVersion + ", got " + version);
        try {
            Class<?> main = storm.getClass();
            configManager = main.getMethod("getBossConfigManager").invoke(storm);
            bossManager = main.getMethod("getBossManager").invoke(storm);
            scheduleManager = main.getMethod("getScheduleConfigManager").invoke(storm);
            if (configManager == null || bossManager == null || scheduleManager == null) return new AdapterHealth(AdapterHealth.Status.INCOMPATIBLE, "STORM managers unavailable");

            getBoss = method(configManager.getClass(), "A", String.class);
            listBosses = method(configManager.getClass(), "B");
            spawnBoss = method(bossManager.getClass(), "A", String.class, Location.class);
            stopBoss = method(bossManager.getClass(), "C", UUID.class);
            managerIsBoss = method(bossManager.getClass(), "A", Entity.class);

            // Verify exact result/definition shapes used by the adapter without creating anything.
            Class<?> spawnResult = spawnBoss.getReturnType();
            method(spawnResult, "A"); // success
            method(spawnResult, "C"); // LivingEntity
            method(spawnResult, "B"); // error
            return AdapterHealth.ok();
        } catch (Throwable error) {
            return new AdapterHealth(AdapterHealth.Status.INCOMPATIBLE, root(error));
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        Method m = type.getMethod(name, args);
        m.setAccessible(true);
        return m;
    }

    @Override public String providerId() { return "stormbossy"; }
    @Override public AdapterHealth health() { return health; }

    @Override public Set<String> bossIds() {
        if (!health.ready()) return Set.of();
        try {
            Object raw = listBosses.invoke(configManager);
            if (!(raw instanceof Collection<?> values)) return Set.of();
            LinkedHashSet<String> out = new LinkedHashSet<>();
            for (Object value : values) if (value != null) out.add(String.valueOf(value));
            return Collections.unmodifiableSet(out);
        } catch (Throwable error) { return Set.of(); }
    }

    @Override
    public AdapterHealth validateBoss(String bossId, int spawnLocation, boolean strictSchedules, boolean strictRewards) {
        if (!health.ready()) return health;
        if (bossId == null || bossId.isBlank()) return new AdapterHealth(AdapterHealth.Status.MISCONFIGURED, "Missing boss-id");
        try {
            Object definition = getBoss.invoke(configManager, bossId);
            if (definition == null) return new AdapterHealth(AdapterHealth.Status.MISCONFIGURED, "Unknown STORM boss-id: " + bossId);
            Method enabled = method(definition.getClass(), "b");
            if (!(Boolean) enabled.invoke(definition)) return new AdapterHealth(AdapterHealth.Status.MISCONFIGURED, "STORM boss is disabled: " + bossId);
            List<?> locations = spawnLocations(definition);
            if (spawnLocation <= 0 || spawnLocation > locations.size()) return new AdapterHealth(AdapterHealth.Status.MISCONFIGURED, "Invalid spawn-location " + spawnLocation + " for " + bossId + " (available: " + locations.size() + ")");
            Location location = locationOf(locations.get(spawnLocation - 1));
            if (location == null || location.getWorld() == null) return new AdapterHealth(AdapterHealth.Status.MISCONFIGURED, "Spawn location world is unavailable for " + bossId);
            if (strictSchedules && hasEnabledNativeSchedules()) return new AdapterHealth(AdapterHealth.Status.MISCONFIGURED, "Native STORMBOSSY scheduler has enabled entries");
            if (strictRewards && hasNativeRewards(definition)) return new AdapterHealth(AdapterHealth.Status.MISCONFIGURED, "Native STORMBOSSY rewards are enabled for " + bossId);
            return AdapterHealth.ok();
        } catch (Throwable error) {
            return new AdapterHealth(AdapterHealth.Status.INCOMPATIBLE, root(error));
        }
    }

    @Override public Optional<Location> spawnLocation(String bossId, int spawnLocation) {
        if (!health.ready()) return Optional.empty();
        try {
            Object definition = getBoss.invoke(configManager, bossId);
            if (definition == null) return Optional.empty();
            List<?> list = spawnLocations(definition);
            if (spawnLocation <= 0 || spawnLocation > list.size()) return Optional.empty();
            return Optional.ofNullable(locationOf(list.get(spawnLocation - 1))).map(Location::clone);
        } catch (Throwable ignored) { return Optional.empty(); }
    }

    @Override public BossSpawnResult spawn(String bossId, int spawnLocation) {
        AdapterHealth validated = validateBoss(bossId, spawnLocation, false, false);
        if (!validated.ready()) return BossSpawnResult.fail(validated.message());
        try {
            Location location = spawnLocation(bossId, spawnLocation).orElse(null);
            if (location == null) return BossSpawnResult.fail("Spawn location unavailable");
            Object result = spawnBoss.invoke(bossManager, bossId, location);
            if (result == null) return BossSpawnResult.fail("STORM returned null spawn result");
            boolean success = (Boolean) method(result.getClass(), "A").invoke(result);
            if (!success) {
                Object error = method(result.getClass(), "B").invoke(result);
                return BossSpawnResult.fail(error == null ? "STORM spawn rejected" : String.valueOf(error));
            }
            Object entityRaw = method(result.getClass(), "C").invoke(result);
            if (!(entityRaw instanceof LivingEntity entity)) return BossSpawnResult.fail("STORM spawn result has no LivingEntity");
            String pdc = entity.getPersistentDataContainer().get(bossKey, PersistentDataType.STRING);
            if (pdc == null || !pdc.equalsIgnoreCase(bossId)) {
                try { stop(entity.getUniqueId()); } catch (Throwable ignored) { }
                return BossSpawnResult.fail("STORM boss PDC storm_boss_entity missing/mismatched");
            }
            return BossSpawnResult.ok(entity.getUniqueId());
        } catch (Throwable error) { return BossSpawnResult.fail(root(error)); }
    }

    @Override public StopBossResult stop(UUID bossEntityId) {
        if (!health.ready() || bossEntityId == null) return StopBossResult.fail("Adapter unavailable");
        try { stopBoss.invoke(bossManager, bossEntityId); return StopBossResult.ok(); }
        catch (Throwable error) { return StopBossResult.fail(root(error)); }
    }

    @Override public boolean isBoss(Entity entity) {
        if (!health.ready() || entity == null) return false;
        try { return (Boolean) managerIsBoss.invoke(bossManager, entity); }
        catch (Throwable ignored) { return bossId(entity).isPresent(); }
    }

    @Override public Optional<String> bossId(Entity entity) {
        if (entity == null || bossKey == null) return Optional.empty();
        try { return Optional.ofNullable(entity.getPersistentDataContainer().get(bossKey, PersistentDataType.STRING)); }
        catch (Throwable ignored) { return Optional.empty(); }
    }

    private List<?> spawnLocations(Object definition) throws Exception {
        Object raw = method(definition.getClass(), "B").invoke(definition);
        return raw instanceof List<?> list ? list : List.of();
    }
    private Location locationOf(Object holder) throws Exception {
        if (holder == null) return null;
        Object raw = method(holder.getClass(), "A").invoke(holder);
        return raw instanceof Location location ? location : null;
    }
    private boolean hasEnabledNativeSchedules() throws Exception {
        Object raw = method(scheduleManager.getClass(), "A").invoke(scheduleManager);
        if (!(raw instanceof Collection<?> schedules)) return false;
        for (Object schedule : schedules) if (schedule != null && (Boolean) method(schedule.getClass(), "S").invoke(schedule)) return true;
        return false;
    }
    private boolean hasNativeRewards(Object definition) throws Exception {
        Object raw = method(definition.getClass(), "e").invoke(definition);
        if (!(raw instanceof Map<?,?> map) || map.isEmpty()) return false;
        for (Object value : map.values()) if (value instanceof Collection<?> c && !c.isEmpty()) return true;
        return false;
    }
    private static String root(Throwable t){ Throwable c=t;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.getClass().getSimpleName():c.getMessage(); }
}
