package hexposterunki.engine;

import hexcustommobs.api.CustomMobsApi;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.PointDef;
import hexposterunki.config.WaveDefinition;
import hexposterunki.mobs.RunTags;
import hexposterunki.persistence.TrackedEntity;
import hexposterunki.util.RandomSource;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Spawns configured waves through the public {@link CustomMobsApi} and stamps every mob with the
 * run/outpost/wave PDC tags. Main thread only.
 */
public final class WaveSpawner {

    private final CustomMobsApi customMobs;
    private final RunTags tags;
    private final RandomSource random;
    private final WaveScaling scaling;
    private final Logger logger;

    public WaveSpawner(CustomMobsApi customMobs, RunTags tags, RandomSource random,
                       WaveScaling scaling, Logger logger) {
        this.customMobs = Objects.requireNonNull(customMobs, "customMobs");
        this.tags = Objects.requireNonNull(tags, "tags");
        this.random = Objects.requireNonNull(random, "random");
        this.scaling = scaling == null ? WaveScaling.none() : scaling;
        this.logger = logger;
    }

    /** Spawns the full wave. */
    public SpawnResult spawnWave(OutpostDefinition outpost, WaveDefinition wave, String runId, int participants) {
        List<TrackedEntity> spawned = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        World world = Bukkit.getWorld(outpost.world());
        if (world == null) {
            problems.add("świat '" + outpost.world() + "' nie jest załadowany");
            return new SpawnResult(spawned, problems);
        }

        for (WaveDefinition.Group group : wave.groups()) {
            if (!customMobs.hasMob(group.mobId())) {
                problems.add("nieznany custom mob '" + group.mobId() + "'");
                continue;
            }
            int count = scaling.scale(group.count(), participants);
            for (int i = 0; i < count; i++) {
                Optional<Location> location = resolveSpawn(world, outpost, group);
                if (location.isEmpty()) {
                    problems.add("brak poprawnego punktu spawnu dla '" + group.mobId() + "'");
                    break;
                }
                Optional<LivingEntity> entity = customMobs.spawn(location.get(), group.mobId());
                if (entity.isEmpty()) {
                    problems.add("HexCustomMobs nie utworzył moba '" + group.mobId() + "'");
                    continue;
                }
                LivingEntity mob = entity.get();
                tags.tagMob(mob, runId, outpost.id(), wave.id());
                keepLoaded(mob);
                spawned.add(new TrackedEntity(mob.getUniqueId(), TrackedEntity.Kind.MOB, group.mobId(), wave.id()));
            }
        }
        return new SpawnResult(spawned, problems);
    }

    /**
     * Spawns exactly {@code amount} additional mobs of this wave, used by recovery to top up a
     * wave whose mobs disappeared while the server was down.
     */
    public SpawnResult spawnMissing(OutpostDefinition outpost, WaveDefinition wave, String runId, int amount) {
        List<TrackedEntity> spawned = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (amount <= 0 || wave.groups().isEmpty()) {
            return new SpawnResult(spawned, problems);
        }
        World world = Bukkit.getWorld(outpost.world());
        if (world == null) {
            problems.add("świat '" + outpost.world() + "' nie jest załadowany");
            return new SpawnResult(spawned, problems);
        }
        // Round-robin over the wave groups keeps the composition close to the original wave.
        List<WaveDefinition.Group> groups = wave.groups().stream()
                .filter(group -> customMobs.hasMob(group.mobId()))
                .toList();
        if (groups.isEmpty()) {
            problems.add("żaden mob fali " + wave.id() + " nie istnieje w HexCustomMobs");
            return new SpawnResult(spawned, problems);
        }
        for (int i = 0; i < amount; i++) {
            WaveDefinition.Group group = groups.get(i % groups.size());
            Optional<Location> location = resolveSpawn(world, outpost, group);
            if (location.isEmpty()) {
                problems.add("brak poprawnego punktu spawnu dla '" + group.mobId() + "'");
                break;
            }
            Optional<LivingEntity> entity = customMobs.spawn(location.get(), group.mobId());
            if (entity.isEmpty()) {
                problems.add("HexCustomMobs nie utworzył moba '" + group.mobId() + "'");
                continue;
            }
            LivingEntity mob = entity.get();
            tags.tagMob(mob, runId, outpost.id(), wave.id());
            keepLoaded(mob);
            spawned.add(new TrackedEntity(mob.getUniqueId(), TrackedEntity.Kind.MOB, group.mobId(), wave.id()));
        }
        return new SpawnResult(spawned, problems);
    }

    /** Structural check for {@code /posterunki validate}: unknown mob ids and unknown spawn points. */
    public List<String> validate(OutpostDefinition outpost, List<WaveDefinition> waves) {
        Set<String> problems = new LinkedHashSet<>();
        for (WaveDefinition wave : waves) {
            for (WaveDefinition.Group group : wave.groups()) {
                if (!customMobs.hasMob(group.mobId())) {
                    problems.add("fala " + wave.id() + ": nieznany custom mob '" + group.mobId() + "'");
                }
                for (String point : group.spawnPoints()) {
                    if (!outpost.spawnPoints().containsKey(point)) {
                        problems.add("fala " + wave.id() + ": posterunek '" + outpost.id()
                                + "' nie ma punktu spawnu '" + point + "'");
                    }
                }
            }
        }
        return List.copyOf(problems);
    }

    /**
     * Event mobs must not despawn on their own. A server implementation that does not support the
     * flag is not a reason to abort a whole wave, so the failure is logged and the spawn stands.
     */
    private void keepLoaded(LivingEntity mob) {
        try {
            mob.setRemoveWhenFarAway(false);
        } catch (RuntimeException exception) {
            logger.warning("[wave] Nie można wyłączyć despawnu moba: " + exception.getMessage());
        }
    }

    private Optional<Location> resolveSpawn(World world, OutpostDefinition outpost, WaveDefinition.Group group) {
        List<String> allowed = group.spawnPoints().isEmpty()
                ? outpost.spawnPointNames()
                : group.spawnPoints().stream().filter(outpost.spawnPoints()::containsKey).toList();
        if (allowed.isEmpty()) {
            return Optional.empty();
        }
        String pointName = allowed.get(random.nextInt(allowed.size()));
        PointDef point = outpost.spawnPoints().get(pointName);
        if (point == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch()));
    }

    /** @param problems human readable Polish reasons, logged once per spawn batch */
    public record SpawnResult(List<TrackedEntity> spawned, List<String> problems) {

        public SpawnResult {
            spawned = List.copyOf(spawned);
            problems = List.copyOf(problems);
        }

        public int count() {
            return spawned.size();
        }
    }
}
