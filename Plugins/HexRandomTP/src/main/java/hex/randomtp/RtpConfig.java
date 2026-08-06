package hex.randomtp;

import hex.core.api.region.RegionKey;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class RtpConfig {
    private final String worldName;
    private final String messagePrefix;
    private final Set<ActivatorPosition> activatorPositions;
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;
    private final long defaultCooldownSeconds;
    private final String cooldownBypassPermission;
    private final Map<String, Long> permissionCooldowns;
    private final boolean generateNewChunks;
    private final int maxAttempts;
    private final Set<Long> forbiddenChunks;
    private final List<ForbiddenArea> forbiddenAreas;
    private final long retryDelayTicks;
    private final int minSurfaceY;
    private final int maxSurfaceY;
    private final boolean respectWorldBorder;
    private final Set<String> forbiddenBiomes;
    private final Set<Material> forbiddenSurfaceBlocks;
    private final boolean excludeAnyHexCoreRegion;
    private final List<RegionKey> forbiddenRegionKeys;
    private final Set<String> forbiddenRegionNamespaces;

    private RtpConfig(
            String worldName,
            String messagePrefix,
            Set<ActivatorPosition> activatorPositions,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            long defaultCooldownSeconds,
            String cooldownBypassPermission,
            Map<String, Long> permissionCooldowns,
            boolean generateNewChunks,
            int maxAttempts,
            Set<Long> forbiddenChunks,
            List<ForbiddenArea> forbiddenAreas,
            long retryDelayTicks,
            int minSurfaceY,
            int maxSurfaceY,
            boolean respectWorldBorder,
            Set<String> forbiddenBiomes,
            Set<Material> forbiddenSurfaceBlocks,
            boolean excludeAnyHexCoreRegion,
            List<RegionKey> forbiddenRegionKeys,
            Set<String> forbiddenRegionNamespaces
    ) {
        this.worldName = worldName;
        this.messagePrefix = messagePrefix;
        this.activatorPositions = Collections.unmodifiableSet(activatorPositions);
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.defaultCooldownSeconds = defaultCooldownSeconds;
        this.cooldownBypassPermission = cooldownBypassPermission;
        this.permissionCooldowns = Collections.unmodifiableMap(permissionCooldowns);
        this.generateNewChunks = generateNewChunks;
        this.maxAttempts = maxAttempts;
        this.forbiddenChunks = Collections.unmodifiableSet(forbiddenChunks);
        this.forbiddenAreas = List.copyOf(forbiddenAreas);
        this.retryDelayTicks = retryDelayTicks;
        this.minSurfaceY = minSurfaceY;
        this.maxSurfaceY = maxSurfaceY;
        this.respectWorldBorder = respectWorldBorder;
        this.forbiddenBiomes = Collections.unmodifiableSet(forbiddenBiomes);
        this.forbiddenSurfaceBlocks = Collections.unmodifiableSet(forbiddenSurfaceBlocks);
        this.excludeAnyHexCoreRegion = excludeAnyHexCoreRegion;
        this.forbiddenRegionKeys = List.copyOf(forbiddenRegionKeys);
        this.forbiddenRegionNamespaces = Collections.unmodifiableSet(forbiddenRegionNamespaces);
    }

    static RtpConfig load(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();

        String worldName = config.getString("world", "world").trim();
        if (worldName.isBlank()) {
            worldName = "world";
            plugin.getLogger().warning("config.yml: 'world' było puste; używam 'world'.");
        }

        Object configuredPrefix = config.get("messages.prefix");
        String messagePrefix = configuredPrefix == null
                ? "<gray>[</gray><aqua><bold>RTP</bold></aqua><gray>]</gray> "
                : configuredPrefix.toString();
        Set<ActivatorPosition> activatorPositions = loadActivatorPositions(plugin, config, worldName);

        int rawMinX = config.getInt("bounds.min-x", -10000);
        int rawMaxX = config.getInt("bounds.max-x", 10000);
        int rawMinZ = config.getInt("bounds.min-z", -10000);
        int rawMaxZ = config.getInt("bounds.max-z", 10000);
        int minX = Math.min(rawMinX, rawMaxX);
        int maxX = Math.max(rawMinX, rawMaxX);
        int minZ = Math.min(rawMinZ, rawMaxZ);
        int maxZ = Math.max(rawMinZ, rawMaxZ);
        if (rawMinX > rawMaxX || rawMinZ > rawMaxZ) {
            plugin.getLogger().warning("Odwrócone granice RTP zostały automatycznie zamienione miejscami.");
        }

        long defaultCooldown = Math.max(0L, config.getLong("cooldown.default-seconds", 60L));
        String bypassPermission = config.getString(
                "cooldown.bypass-permission",
                "hexrandomtp.cooldown.bypass"
        ).trim();

        Map<String, Long> permissionCooldowns = loadPermissionCooldowns(plugin, config, defaultCooldown);

        boolean generateNewChunks = config.getBoolean("search.generate-new-chunks", true);
        int maxAttempts = Math.max(1, config.getInt("search.max-attempts", 50));
        Set<Long> forbiddenChunks = loadForbiddenChunks(plugin, config);
        List<ForbiddenArea> forbiddenAreas = loadForbiddenAreas(plugin, config);
        long retryDelayTicks = Math.max(0L, config.getLong("search.retry-delay-ticks", 1L));
        int rawMinY = config.getInt("search.min-surface-y", -64);
        int rawMaxY = config.getInt("search.max-surface-y", 317);
        int minSurfaceY = Math.min(rawMinY, rawMaxY);
        int maxSurfaceY = Math.max(rawMinY, rawMaxY);
        boolean respectWorldBorder = config.getBoolean("search.respect-world-border", true);

        Set<String> forbiddenBiomes = new LinkedHashSet<>();
        for (String rawBiome : config.getStringList("search.forbidden-biomes")) {
            String biome = normalizeBiome(rawBiome);
            if (!biome.isBlank()) {
                forbiddenBiomes.add(biome);
            }
        }

        Set<Material> forbiddenSurfaceBlocks = new LinkedHashSet<>();
        for (String rawMaterial : config.getStringList("search.forbidden-surface-blocks")) {
            Material material = Material.matchMaterial(rawMaterial.trim());
            if (material == null) {
                plugin.getLogger().warning("Nieznany materiał w search.forbidden-surface-blocks: " + rawMaterial);
            } else {
                forbiddenSurfaceBlocks.add(material);
            }
        }

        boolean excludeAnyRegion = config.getBoolean("regions.exclude-any-hexcore-region", false);
        List<RegionKey> regionKeys = new ArrayList<>();
        for (String rawKey : config.getStringList("regions.forbidden-keys")) {
            try {
                regionKeys.add(RegionKey.parse(rawKey));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Nieprawidłowy klucz regionu HexCore '" + rawKey + "': " + exception.getMessage());
            }
        }

        Set<String> regionNamespaces = new LinkedHashSet<>();
        for (String rawNamespace : config.getStringList("regions.forbidden-namespaces")) {
            String namespace = rawNamespace.trim().toLowerCase(Locale.ROOT);
            if (!namespace.isBlank()) {
                regionNamespaces.add(namespace);
            }
        }

        return new RtpConfig(
                worldName,
                messagePrefix,
                activatorPositions,
                minX,
                maxX,
                minZ,
                maxZ,
                defaultCooldown,
                bypassPermission,
                permissionCooldowns,
                generateNewChunks,
                maxAttempts,
                forbiddenChunks,
                forbiddenAreas,
                retryDelayTicks,
                minSurfaceY,
                maxSurfaceY,
                respectWorldBorder,
                forbiddenBiomes,
                forbiddenSurfaceBlocks,
                excludeAnyRegion,
                regionKeys,
                regionNamespaces
        );
    }

    private static Set<ActivatorPosition> loadActivatorPositions(
            JavaPlugin plugin,
            FileConfiguration config,
            String defaultWorld
    ) {
        Set<ActivatorPosition> result = new LinkedHashSet<>();
        List<Map<?, ?>> configuredPositions = config.getMapList("activators.positions");

        for (int index = 0; index < configuredPositions.size(); index++) {
            Map<?, ?> entry = configuredPositions.get(index);
            Integer x = intValue(entry.get("x"));
            Integer y = intValue(entry.get("y"));
            Integer z = intValue(entry.get("z"));
            if (x == null || y == null || z == null) {
                plugin.getLogger().warning("Nieprawidłowy wpis activators.positions[" + index
                        + "]: wymagane są całkowite x, y i z.");
                continue;
            }

            String configuredWorld = stringValue(entry.get("world"));
            String world = configuredWorld.isBlank() ? defaultWorld : configuredWorld;
            result.add(new ActivatorPosition(world, x, y, z));
        }

        return result;
    }

    private static Map<String, Long> loadPermissionCooldowns(
            JavaPlugin plugin,
            FileConfiguration config,
            long defaultCooldown
    ) {
        Map<String, Long> result = new LinkedHashMap<>();

        // Nowy, prosty format. Lista pozwala bezpiecznie używać kropek w nazwach permission.
        List<Map<?, ?>> configuredEntries = config.getMapList("cooldown.permission-cooldowns");
        for (int index = 0; index < configuredEntries.size(); index++) {
            Map<?, ?> entry = configuredEntries.get(index);
            String permission = stringValue(entry.get("permission"));
            if (permission.isBlank()) {
                plugin.getLogger().warning("Brak permission w cooldown.permission-cooldowns[" + index + "].");
                continue;
            }

            Long seconds = longValue(entry.get("seconds"));
            if (seconds == null) {
                plugin.getLogger().warning("Nieprawidłowe seconds w cooldown.permission-cooldowns[" + index
                        + "]; używam cooldownu domyślnego.");
                seconds = defaultCooldown;
            }
            result.put(permission, Math.max(0L, seconds));
        }

        // Zgodność wsteczna ze starszym formatem permission-overrides.
        ConfigurationSection legacySection = config.getConfigurationSection("cooldown.permission-overrides");
        if (legacySection != null) {
            for (String entryId : legacySection.getKeys(false)) {
                ConfigurationSection entry = legacySection.getConfigurationSection(entryId);
                if (entry == null) {
                    plugin.getLogger().warning("Nieprawidłowy wpis cooldown.permission-overrides." + entryId
                            + ": oczekiwano sekcji z polami permission i seconds.");
                    continue;
                }

                String permission = entry.getString("permission", "").trim();
                if (permission.isBlank()) {
                    plugin.getLogger().warning("Brak permission w cooldown.permission-overrides." + entryId);
                    continue;
                }
                result.putIfAbsent(permission, Math.max(0L, entry.getLong("seconds", defaultCooldown)));
            }
        }

        return result;
    }

    private static Set<Long> loadForbiddenChunks(JavaPlugin plugin, FileConfiguration config) {
        Set<Long> forbiddenChunks = new LinkedHashSet<>();
        for (String rawChunk : config.getStringList("search.forbidden-chunks")) {
            String[] parts = rawChunk.trim().split(",", 2);
            if (parts.length != 2) {
                plugin.getLogger().warning("Nieprawidłowy wpis search.forbidden-chunks: '" + rawChunk
                        + "'. Oczekiwano formatu chunkX,chunkZ.");
                continue;
            }
            try {
                int chunkX = Integer.parseInt(parts[0].trim());
                int chunkZ = Integer.parseInt(parts[1].trim());
                forbiddenChunks.add(chunkKey(chunkX, chunkZ));
            } catch (NumberFormatException exception) {
                plugin.getLogger().warning("Nieprawidłowy wpis search.forbidden-chunks: '" + rawChunk
                        + "'. Współrzędne chunków muszą być liczbami całkowitymi.");
            }
        }
        return forbiddenChunks;
    }

    private static List<ForbiddenArea> loadForbiddenAreas(JavaPlugin plugin, FileConfiguration config) {
        List<ForbiddenArea> result = new ArrayList<>();
        List<Map<?, ?>> configuredAreas = config.getMapList("search.forbidden-areas");

        for (int index = 0; index < configuredAreas.size(); index++) {
            Map<?, ?> entry = configuredAreas.get(index);
            Integer x1 = intValue(entry.get("x1"));
            Integer z1 = intValue(entry.get("z1"));
            Integer x2 = intValue(entry.get("x2"));
            Integer z2 = intValue(entry.get("z2"));

            if (x1 == null || z1 == null || x2 == null || z2 == null) {
                plugin.getLogger().warning("Nieprawidłowy wpis search.forbidden-areas[" + index
                        + "]: wymagane są całkowite x1, z1, x2 i z2.");
                continue;
            }

            String configuredName = stringValue(entry.get("name"));
            String name = configuredName.isBlank() ? "area-" + (index + 1) : configuredName;
            result.add(new ForbiddenArea(
                    name,
                    Math.min(x1, x2),
                    Math.max(x1, x2),
                    Math.min(z1, z2),
                    Math.max(z1, z2)
            ));
        }

        return result;
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static String normalizeBiome(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    String worldName() { return worldName; }
    String messagePrefix() { return messagePrefix; }
    boolean isActivator(String world, int x, int y, int z) {
        return activatorPositions.contains(new ActivatorPosition(world, x, y, z));
    }
    int activatorCount() { return activatorPositions.size(); }
    int minX() { return minX; }
    int maxX() { return maxX; }
    int minZ() { return minZ; }
    int maxZ() { return maxZ; }
    long defaultCooldownSeconds() { return defaultCooldownSeconds; }
    String cooldownBypassPermission() { return cooldownBypassPermission; }
    Map<String, Long> permissionCooldowns() { return permissionCooldowns; }
    boolean generateNewChunks() { return generateNewChunks; }
    int maxAttempts() { return maxAttempts; }
    boolean isForbiddenChunk(int chunkX, int chunkZ) { return forbiddenChunks.contains(chunkKey(chunkX, chunkZ)); }
    boolean isForbiddenCoordinate(int x, int z) {
        for (ForbiddenArea area : forbiddenAreas) {
            if (area.contains(x, z)) {
                return true;
            }
        }
        return false;
    }
    int forbiddenAreaCount() { return forbiddenAreas.size(); }
    long retryDelayTicks() { return retryDelayTicks; }
    int minSurfaceY() { return minSurfaceY; }
    int maxSurfaceY() { return maxSurfaceY; }
    boolean respectWorldBorder() { return respectWorldBorder; }
    Set<String> forbiddenBiomes() { return forbiddenBiomes; }
    Set<Material> forbiddenSurfaceBlocks() { return forbiddenSurfaceBlocks; }
    boolean excludeAnyHexCoreRegion() { return excludeAnyHexCoreRegion; }
    List<RegionKey> forbiddenRegionKeys() { return forbiddenRegionKeys; }
    Set<String> forbiddenRegionNamespaces() { return forbiddenRegionNamespaces; }

    private record ForbiddenArea(String name, int minX, int maxX, int minZ, int maxZ) {
        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private record ActivatorPosition(String world, int x, int y, int z) { }
}
