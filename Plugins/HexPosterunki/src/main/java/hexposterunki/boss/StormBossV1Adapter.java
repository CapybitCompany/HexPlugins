package hexposterunki.boss;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Version-pinned bridge to the installed STORMBOSSY 1.0 jar.
 *
 * <p>No {@code pl.ZIFFY.*} class is linked at compile time - STORMBOSSY is a third-party plugin
 * that only exists at runtime. Every method handle is resolved and verified once in {@link #bind()};
 * an unexpected shape makes the adapter report a non-READY {@link AdapterHealth}, which stops the
 * whole event operation instead of letting it run half-broken.
 *
 * <p>Deliberate scope limits:
 * <ul>
 *   <li>the boss is spawned at the outpost's dynamic boss position, never at a STORM spawn slot;</li>
 *   <li>STORMBOSSY's own scheduler must be disabled - HexPosterunki owns the scheduling;</li>
 *   <li>STORMBOSSY's native reward system stays enabled and remains the only source of boss
 *       rewards. HexPosterunki never duplicates or reimplements them.</li>
 * </ul>
 */
public final class StormBossV1Adapter implements BossEngineAdapter {

    private final Plugin storm;
    private final String supportedVersion;
    private final boolean requireNativeSchedulesOff;
    private final boolean expectNativeRewards;
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

    public StormBossV1Adapter(Plugin storm, String supportedVersion,
                              boolean requireNativeSchedulesOff, boolean expectNativeRewards) {
        this.storm = storm;
        this.supportedVersion = supportedVersion == null || supportedVersion.isBlank() ? "1.0" : supportedVersion;
        this.requireNativeSchedulesOff = requireNativeSchedulesOff;
        this.expectNativeRewards = expectNativeRewards;
        this.bossKey = storm == null ? null : new NamespacedKey(storm, "storm_boss_entity");
        this.health = bind();
    }

    private AdapterHealth bind() {
        if (storm == null || !storm.isEnabled()) {
            return AdapterHealth.unavailable("STORMBOSSY nie jest włączone");
        }
        String version = storm.getDescription().getVersion();
        if (!supportedVersion.equals(version)) {
            return new AdapterHealth(AdapterHealth.Status.UNSUPPORTED_VERSION,
                    "Oczekiwano STORMBOSSY " + supportedVersion + ", znaleziono " + version);
        }
        try {
            Class<?> main = storm.getClass();
            configManager = main.getMethod("getBossConfigManager").invoke(storm);
            bossManager = main.getMethod("getBossManager").invoke(storm);
            scheduleManager = main.getMethod("getScheduleConfigManager").invoke(storm);
            if (configManager == null || bossManager == null || scheduleManager == null) {
                return new AdapterHealth(AdapterHealth.Status.INCOMPATIBLE, "Managery STORMBOSSY niedostępne");
            }

            getBoss = method(configManager.getClass(), "A", String.class);
            listBosses = method(configManager.getClass(), "B");
            spawnBoss = method(bossManager.getClass(), "A", String.class, Location.class);
            stopBoss = method(bossManager.getClass(), "C", UUID.class);
            managerIsBoss = method(bossManager.getClass(), "A", Entity.class);

            // Verify the exact result shape without instantiating anything.
            Class<?> spawnResult = spawnBoss.getReturnType();
            method(spawnResult, "A"); // success flag
            method(spawnResult, "C"); // spawned LivingEntity
            method(spawnResult, "B"); // error message

            if (requireNativeSchedulesOff && hasEnabledNativeSchedules()) {
                return new AdapterHealth(AdapterHealth.Status.MISCONFIGURED,
                        "Natywny harmonogram STORMBOSSY ma włączone wpisy - wyłącz go, HexPosterunki steruje spawnem bossa");
            }
            return AdapterHealth.ok();
        } catch (Throwable error) {
            return new AdapterHealth(AdapterHealth.Status.INCOMPATIBLE, root(error));
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        Method found = type.getMethod(name, args);
        found.setAccessible(true);
        return found;
    }

    @Override
    public String providerId() {
        return "stormbossy";
    }

    @Override
    public AdapterHealth health() {
        return health;
    }

    /** Re-resolves the bridge, used by {@code /posterunki reload} and {@code /posterunki validate}. */
    public AdapterHealth rebind() {
        this.health = bind();
        return this.health;
    }

    @Override
    public Set<String> bossIds() {
        if (!health.ready()) {
            return Set.of();
        }
        try {
            Object raw = listBosses.invoke(configManager);
            if (!(raw instanceof Collection<?> values)) {
                return Set.of();
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (Object value : values) {
                if (value != null) {
                    ids.add(String.valueOf(value));
                }
            }
            return Collections.unmodifiableSet(ids);
        } catch (Throwable ignored) {
            return Set.of();
        }
    }

    @Override
    public AdapterHealth validateBoss(String bossId) {
        if (!health.ready()) {
            return health;
        }
        if (bossId == null || bossId.isBlank()) {
            return new AdapterHealth(AdapterHealth.Status.MISCONFIGURED, "Pusty boss-id");
        }
        try {
            Object definition = getBoss.invoke(configManager, bossId);
            if (definition == null) {
                return new AdapterHealth(AdapterHealth.Status.MISCONFIGURED, "Nieznany boss STORMBOSSY: " + bossId);
            }
            Method enabled = method(definition.getClass(), "b");
            if (!(Boolean) enabled.invoke(definition)) {
                return new AdapterHealth(AdapterHealth.Status.MISCONFIGURED, "Boss STORMBOSSY wyłączony: " + bossId);
            }
            if (expectNativeRewards && !hasNativeRewards(definition)) {
                // Not fatal: HexPosterunki must never pay boss rewards itself, so this is only a hint.
                return new AdapterHealth(AdapterHealth.Status.READY,
                        "OSTRZEŻENIE: boss '" + bossId + "' nie ma natywnych nagród STORMBOSSY");
            }
            return AdapterHealth.ok();
        } catch (Throwable error) {
            return new AdapterHealth(AdapterHealth.Status.INCOMPATIBLE, root(error));
        }
    }

    @Override
    public BossSpawnResult spawn(String bossId, Location location) {
        if (!health.ready()) {
            return BossSpawnResult.fail(health.message());
        }
        if (location == null || location.getWorld() == null) {
            return BossSpawnResult.fail("Brak pozycji spawnu bossa");
        }
        AdapterHealth validated = validateBoss(bossId);
        if (!validated.ready()) {
            return BossSpawnResult.fail(validated.message());
        }
        try {
            Object result = spawnBoss.invoke(bossManager, bossId, location);
            if (result == null) {
                return BossSpawnResult.fail("STORMBOSSY zwróciło pusty wynik spawnu");
            }
            boolean success = (Boolean) method(result.getClass(), "A").invoke(result);
            if (!success) {
                Object error = method(result.getClass(), "B").invoke(result);
                return BossSpawnResult.fail(error == null ? "STORMBOSSY odrzuciło spawn" : String.valueOf(error));
            }
            Object entityRaw = method(result.getClass(), "C").invoke(result);
            if (!(entityRaw instanceof LivingEntity entity)) {
                return BossSpawnResult.fail("Wynik spawnu STORMBOSSY nie zawiera LivingEntity");
            }
            String pdc = entity.getPersistentDataContainer().get(bossKey, PersistentDataType.STRING);
            if (pdc == null || !pdc.equalsIgnoreCase(bossId)) {
                try {
                    stop(entity.getUniqueId());
                } catch (Throwable ignored) {
                    // Best effort cleanup; the failure is reported below either way.
                }
                return BossSpawnResult.fail("Brak lub niezgodny PDC storm_boss_entity");
            }
            return BossSpawnResult.ok(entity.getUniqueId());
        } catch (Throwable error) {
            return BossSpawnResult.fail(root(error));
        }
    }

    @Override
    public StopBossResult stop(UUID bossEntityId) {
        if (!health.ready() || bossEntityId == null) {
            return StopBossResult.fail("Adapter STORMBOSSY niedostępny");
        }
        try {
            stopBoss.invoke(bossManager, bossEntityId);
            return StopBossResult.ok();
        } catch (Throwable error) {
            return StopBossResult.fail(root(error));
        }
    }

    @Override
    public boolean isBoss(Entity entity) {
        if (!health.ready() || entity == null) {
            return false;
        }
        try {
            return (Boolean) managerIsBoss.invoke(bossManager, entity);
        } catch (Throwable ignored) {
            return bossId(entity).isPresent();
        }
    }

    @Override
    public Optional<String> bossId(Entity entity) {
        if (entity == null || bossKey == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(entity.getPersistentDataContainer().get(bossKey, PersistentDataType.STRING));
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    private boolean hasEnabledNativeSchedules() throws Exception {
        Object raw = method(scheduleManager.getClass(), "A").invoke(scheduleManager);
        if (!(raw instanceof Collection<?> schedules)) {
            return false;
        }
        for (Object schedule : schedules) {
            if (schedule != null && (Boolean) method(schedule.getClass(), "S").invoke(schedule)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNativeRewards(Object definition) {
        try {
            Object raw = method(definition.getClass(), "e").invoke(definition);
            if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
                return false;
            }
            for (Object value : map.values()) {
                if (value instanceof Collection<?> collection && !collection.isEmpty()) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            // Reward introspection is advisory only; never fail the adapter because of it.
            return true;
        }
    }

    /** Boss ids configured here but unknown to the installed STORMBOSSY jar. */
    public List<String> unknownBossIds(Collection<String> configuredIds) {
        Set<String> known = bossIds();
        return configuredIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .filter(id -> known.stream().noneMatch(candidate -> candidate.equalsIgnoreCase(id)))
                .toList();
    }

    private static String root(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
