package hexposterunki.persistence;

import hexposterunki.config.BlockVec;
import hexposterunki.config.Cuboid;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.PointDef;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stores the outpost definition a run was started with next to the run itself.
 *
 * <p>A reload may move or remove an outpost while a run is active; the run keeps its geometry. That
 * geometry must survive a restart as well, so it is written in the same transaction as the run state
 * and read back by recovery instead of re-resolving the id against the current catalog.
 *
 * <p>Plain YAML, readable by an administrator. Named points and containers are stored as lists, so a
 * name containing a dot is never mistaken for a nested YAML path. Coordinates are written with Java's
 * shortest round-trip representation, so a decoded definition is {@code equals} to the encoded one.
 */
public final class OutpostDefinitionCodec {

    /** Bumped whenever the stored shape changes; unknown versions are rejected, never guessed. */
    public static final int FORMAT = 1;

    private OutpostDefinitionCodec() {
    }

    public static String encode(OutpostDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT);
        yaml.set("id", definition.id());
        yaml.set("display-name", definition.displayName());
        yaml.set("world", definition.world());
        Cuboid region = definition.region();
        yaml.set("region.min", new BlockVec(region.minX(), region.minY(), region.minZ()).toCsv());
        yaml.set("region.max", new BlockVec(region.maxX(), region.maxY(), region.maxZ()).toCsv());
        yaml.set("center", point(definition.center()));
        yaml.set("boss-spawn", point(definition.bossSpawn()));
        List<Map<String, Object>> spawnPoints = new ArrayList<>();
        definition.spawnPoints().forEach((name, point) -> spawnPoints.add(entry(name, point(point))));
        yaml.set("spawn-points", spawnPoints);
        List<Map<String, Object>> containers = new ArrayList<>();
        definition.lootContainers().forEach((name, position) -> containers.add(entry(name, position.toCsv())));
        yaml.set("loot-containers", containers);
        yaml.set("weight", definition.weight());
        return yaml.saveToString();
    }

    /**
     * @throws IllegalArgumentException when the text is not a stored definition of a known format;
     *                                  the caller must then treat the geometry as unknown
     */
    public static OutpostDefinition decode(String stored) {
        if (stored == null || stored.isBlank()) {
            throw new IllegalArgumentException("brak zapisanej definicji posterunku");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(stored);
        } catch (InvalidConfigurationException exception) {
            throw new IllegalArgumentException("uszkodzona definicja posterunku: " + exception.getMessage(), exception);
        }
        int format = yaml.getInt("format", -1);
        if (format != FORMAT) {
            throw new IllegalArgumentException("nieznany format zapisanej definicji posterunku: " + format);
        }
        String id = required(yaml, "id");
        String world = required(yaml, "world");
        Cuboid region = Cuboid.of(world, BlockVec.parse(required(yaml, "region.min")),
                BlockVec.parse(required(yaml, "region.max")));
        Map<String, PointDef> spawnPoints = new LinkedHashMap<>();
        for (Map<?, ?> entry : yaml.getMapList("spawn-points")) {
            spawnPoints.put(name(entry), PointDef.parse(value(entry)));
        }
        Map<String, BlockVec> containers = new LinkedHashMap<>();
        for (Map<?, ?> entry : yaml.getMapList("loot-containers")) {
            containers.put(name(entry), BlockVec.parse(value(entry)));
        }
        return new OutpostDefinition(id, yaml.getString("display-name", id), world, region,
                PointDef.parse(required(yaml, "center")), PointDef.parse(required(yaml, "boss-spawn")),
                spawnPoints, containers, yaml.getInt("weight", 0));
    }

    private static String point(PointDef point) {
        return point.x() + "," + point.y() + "," + point.z() + "," + point.yaw() + "," + point.pitch();
    }

    private static Map<String, Object> entry(String name, String value) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("value", value);
        return entry;
    }

    private static String required(YamlConfiguration yaml, String path) {
        String value = yaml.getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("zapisana definicja posterunku nie ma pola '" + path + "'");
        }
        return value;
    }

    private static String name(Map<?, ?> entry) {
        Object name = entry.get("name");
        if (name == null) {
            throw new IllegalArgumentException("zapisany punkt posterunku nie ma nazwy");
        }
        return name.toString();
    }

    private static String value(Map<?, ?> entry) {
        Object value = entry.get("value");
        if (value == null) {
            throw new IllegalArgumentException("zapisany punkt '" + entry.get("name") + "' nie ma współrzędnych");
        }
        return value.toString();
    }
}
