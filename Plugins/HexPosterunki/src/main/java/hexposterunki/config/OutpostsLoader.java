package hexposterunki.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Parses and validates {@code outposts.yml}. A broken location never disables the plugin: it is
 * skipped and reported, so the remaining fortresses keep working.
 */
public final class OutpostsLoader {

    private final Predicate<String> worldExists;
    private final HeightBounds heightBounds;

    /**
     * @param worldExists  world-name check; injected so validation can run without a server
     * @param heightBounds build-height limits per world, also injected for testability
     */
    public OutpostsLoader(Predicate<String> worldExists, HeightBounds heightBounds) {
        this.worldExists = worldExists == null ? name -> true : worldExists;
        this.heightBounds = heightBounds == null ? world -> new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE} : heightBounds;
    }

    public OutpostCatalog load(FileConfiguration configuration) {
        ValidationReport report = new ValidationReport();
        Map<String, OutpostDefinition> accepted = new LinkedHashMap<>();

        ConfigurationSection root = configuration.getConfigurationSection("outposts");
        if (root == null) {
            report.warn("Sekcja 'outposts' nie istnieje w outposts.yml.");
            return new OutpostCatalog(accepted, report);
        }

        for (String rawId : root.getKeys(false)) {
            String id = rawId.trim().toLowerCase(Locale.ROOT);
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) {
                report.skip(id, "wpis nie jest sekcją YAML");
                continue;
            }
            try {
                OutpostDefinition definition = parse(id, section);
                validate(definition);
                accepted.put(id, definition);
                report.accept(id);
            } catch (IllegalArgumentException exception) {
                report.skip(id, exception.getMessage());
            }
        }
        return new OutpostCatalog(accepted, report);
    }

    private OutpostDefinition parse(String id, ConfigurationSection section) {
        String displayName = section.getString("display-name", id);
        String world = section.getString("world");
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("brak pola 'world'");
        }

        ConfigurationSection regionSection = section.getConfigurationSection("region");
        if (regionSection == null) {
            throw new IllegalArgumentException("brak sekcji 'region'");
        }
        BlockVec min = BlockVec.parse(regionSection.getString("min"));
        BlockVec max = BlockVec.parse(regionSection.getString("max"));
        Cuboid region = Cuboid.of(world, min, max);

        PointDef center = PointDef.parse(section.getString("center"));
        String bossSpawnRaw = section.getString("boss-spawn");
        PointDef bossSpawn = bossSpawnRaw == null || bossSpawnRaw.isBlank() ? center : PointDef.parse(bossSpawnRaw);

        Map<String, PointDef> spawnPoints = new LinkedHashMap<>();
        ConfigurationSection spawnSection = section.getConfigurationSection("spawn-points");
        if (spawnSection != null) {
            for (String key : spawnSection.getKeys(false)) {
                spawnPoints.put(key.trim().toLowerCase(Locale.ROOT), PointDef.parse(spawnSection.getString(key)));
            }
        }

        Map<String, BlockVec> lootContainers = new LinkedHashMap<>();
        ConfigurationSection lootSection = section.getConfigurationSection("loot-containers");
        if (lootSection != null) {
            for (String key : lootSection.getKeys(false)) {
                lootContainers.put(key.trim().toLowerCase(Locale.ROOT), BlockVec.parse(lootSection.getString(key)));
            }
        }

        int weight = section.getInt("weight", 1);
        return new OutpostDefinition(id, displayName, world, region, center, bossSpawn,
                spawnPoints, lootContainers, weight);
    }

    /** Structural validation; throws with a Polish-readable reason for the admin log. */
    public void validate(OutpostDefinition definition) {
        if (!worldExists.test(definition.world())) {
            throw new IllegalArgumentException("świat '" + definition.world() + "' nie istnieje");
        }
        Cuboid region = definition.region();
        if (region.volume() <= 0L) {
            throw new IllegalArgumentException("region ma zerową objętość");
        }
        int[] bounds = heightBounds.of(definition.world());
        if (region.minY() < bounds[0] || region.maxY() > bounds[1]) {
            throw new IllegalArgumentException("region wykracza poza wysokość świata ("
                    + bounds[0] + ".." + bounds[1] + ")");
        }
        if (!region.contains(definition.center())) {
            throw new IllegalArgumentException("punkt 'center' leży poza regionem");
        }
        if (!region.contains(definition.bossSpawn())) {
            throw new IllegalArgumentException("punkt 'boss-spawn' leży poza regionem");
        }
        if (definition.spawnPoints().isEmpty()) {
            throw new IllegalArgumentException("brak punktów 'spawn-points'");
        }
        for (Map.Entry<String, PointDef> entry : definition.spawnPoints().entrySet()) {
            if (!region.contains(entry.getValue())) {
                throw new IllegalArgumentException("punkt spawnu '" + entry.getKey() + "' leży poza regionem");
            }
        }
        for (Map.Entry<String, BlockVec> entry : definition.lootContainers().entrySet()) {
            if (!region.contains(entry.getValue())) {
                throw new IllegalArgumentException("kontener lootu '" + entry.getKey() + "' leży poza regionem");
            }
        }
    }

    /** Build-height limits of a world, as {@code [minY, maxY]}. */
    @FunctionalInterface
    public interface HeightBounds {
        int[] of(String world);
    }
}
