package hexposterunki.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One permanently built fortress. The plugin never creates or removes the build itself; it only
 * uses these coordinates to run an encounter inside an existing structure.
 *
 * @param spawnPoints    named mob spawn points referenced by wave groups
 * @param lootContainers named container positions filled once per run
 * @param weight         relative chance of being picked; higher means more often
 */
public record OutpostDefinition(
        String id,
        String displayName,
        String world,
        Cuboid region,
        PointDef center,
        PointDef bossSpawn,
        Map<String, PointDef> spawnPoints,
        Map<String, BlockVec> lootContainers,
        int weight
) {

    public OutpostDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(bossSpawn, "bossSpawn");
        spawnPoints = spawnPoints == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(spawnPoints));
        lootContainers = lootContainers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(lootContainers));
        weight = Math.max(0, weight);
    }

    public List<String> spawnPointNames() {
        return List.copyOf(spawnPoints.keySet());
    }
}
