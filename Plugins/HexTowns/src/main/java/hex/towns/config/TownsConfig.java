package hex.towns.config;

import org.bukkit.Material;
import org.bukkit.World;
import hex.towns.service.PlayerResetMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TownsConfig {
    private final Set<String> worldWhitelist;
    private final int initialRadius;
    private final int maxChunks;
    private final boolean dynamicChunkLimitEnabled;
    private final int maximumChunks;
    private final int chunkLimitCacheSeconds;
    private final String chunkVipPermission;
    private final String chunkSvipPermission;
    private final String chunkElitePermission;
    private final Set<String> chunkElitePermissions;
    private final int chunkVipBonus;
    private final int chunkSvipBonus;
    private final int chunkEliteBonus;
    private final boolean townShapeEnabled;
    private final int baseMaxWidth;
    private final int baseMaxHeight;
    private final int expandedThresholdChunks;
    private final int expandedMaxWidth;
    private final int expandedMaxHeight;
    private final int bufferChunks;
    private final int minDistanceChunks;
    private final int outsiderBuildBufferChunks;
    private final List<BlockedArea> blockedCreationAreas;
    private final List<String> blockedCreationRegions;
    private final boolean overworldOnly;
    private final boolean creationConfirmRequired;
    private final boolean heartAllowExistingTownPlacementForTests;
    private final String defaultNameTemplate;
    private final String playerTownNoTownValue;
    private final int maxNameLength;
    private final int startingGrowthPoints;
    private final boolean growthSyncEnabled;
    private final int growthSyncIntervalTicks;
    private final int maxMembers;
    private final boolean dynamicMemberLimitEnabled;
    private final int maximumMembers;
    private final int memberLimitCacheSeconds;
    private final int memberLimitLookupTimeoutMillis;
    private final String vipPermission;
    private final String svipPermission;
    private final String elitePermission;
    private final Set<String> elitePermissions;
    private final int vipMemberBonus;
    private final int svipMemberBonus;
    private final int eliteMemberBonus;
    private final int requestTtlSeconds;
    private final int confirmWindowSeconds;
    private final int visualRadiusChunks;
    private final int visualRefreshTicks;
    private final int visualStateRefreshTicks;
    private final int visualPillarHeight;
    private final int visualPillarStep;
    private final int visualEdgeStep;
    private final boolean visualExtendToWorldMin;
    private final boolean visualVerticalEdgeWalls;
    private final boolean visualShowTopFrame;
    private final int visualMaxBlocksPerTickGlobal;
    private final float visualDisplayWidth;
    private final float visualEdgeThickness;
    private final float visualDisplayViewRange;
    private final int mapRadiusChunks;
    private final int mapCooldownSeconds;
    private final boolean mapPreventDuplicates;
    private final int bucketSize;
    private final boolean protectionBlockPlace;
    private final boolean protectionBlockBreak;
    private final boolean protectionInteractContainers;
    private final boolean protectionInteractDoors;
    private final boolean protectionInteractSwitches;
    private final boolean protectionPvp;
    private final boolean protectionExplosion;
    private final boolean protectionAllowFireSpread;
    private final boolean protectionAllowMemberIgnite;
    private final boolean protectionMobs;
    private final int itemPickupWindowSeconds;
    private final boolean mobBlockChangesEnabled;
    private final Set<String> blockedMobBlockChangeEntities;
    private final PlayerResetMode leaveResetMode;
    private final PlayerResetMode kickResetMode;
    private final PlayerResetMode destroyResetMode;
    private final int coopRequestPurgeIntervalSeconds;
    private final long cleanupRetryIntervalTicks;
    private final Material visualBlock;

    private TownsConfig(FileConfiguration config) {
        this.worldWhitelist = new HashSet<>(config.getStringList("towns.world-whitelist"));
        this.initialRadius = Math.max(0, config.getInt("towns.size.initial-radius", 1));
        this.maxChunks = Math.max(1, config.getInt("towns.size.max-chunks", 49));
        this.dynamicChunkLimitEnabled = config.getBoolean("towns.size.dynamic-limit.enabled", true);
        this.maximumChunks = Math.max(this.maxChunks, config.getInt("towns.size.dynamic-limit.maximum-chunks", 69));
        this.chunkLimitCacheSeconds = Math.max(1, config.getInt("towns.size.dynamic-limit.cache-seconds", 300));
        this.chunkVipPermission = config.getString("towns.size.dynamic-limit.permissions.vip", config.getString("towns.coop.dynamic-limit.permissions.vip", "nte.vip"));
        this.chunkSvipPermission = config.getString("towns.size.dynamic-limit.permissions.svip", config.getString("towns.coop.dynamic-limit.permissions.svip", "nte.svip"));
        this.chunkElitePermission = config.getString("towns.size.dynamic-limit.permissions.elite", config.getString("towns.coop.dynamic-limit.permissions.elite", "nte.elita"));
        this.chunkElitePermissions = loadPermissionAliases(config, "towns.size.dynamic-limit.permissions.elite-aliases", this.chunkElitePermission, "nte.media");
        this.chunkVipBonus = Math.max(0, config.getInt("towns.size.dynamic-limit.bonuses.vip", 1));
        this.chunkSvipBonus = Math.max(0, config.getInt("towns.size.dynamic-limit.bonuses.svip", 1));
        this.chunkEliteBonus = Math.max(0, config.getInt("towns.size.dynamic-limit.bonuses.elite", 2));
        this.townShapeEnabled = config.getBoolean("towns.size.shape.enabled", true);
        this.baseMaxWidth = Math.max(1, config.getInt("towns.size.shape.base-max-width", 10));
        this.baseMaxHeight = Math.max(1, config.getInt("towns.size.shape.base-max-height", 10));
        this.expandedThresholdChunks = Math.max(this.maxChunks + 1, config.getInt("towns.size.shape.expanded-threshold-chunks", 50));
        this.expandedMaxWidth = Math.max(this.baseMaxWidth, config.getInt("towns.size.shape.expanded-max-width", 12));
        this.expandedMaxHeight = Math.max(this.baseMaxHeight, config.getInt("towns.size.shape.expanded-max-height", 12));
        this.bufferChunks = Math.max(1, config.getInt("towns.size.buffer-chunks-between-towns", 1));
        this.minDistanceChunks = Math.max(0, config.getInt("towns.creation.min-heart-distance-chunks",
                config.getInt("towns.creation.min-distance-chunks", 16)));
        this.outsiderBuildBufferChunks = Math.max(0, config.getInt("towns.protection.outsider-build-buffer-chunks", 2));
        this.blockedCreationAreas = List.copyOf(loadBlockedCreationAreas(config));
        this.blockedCreationRegions = config.getStringList("towns.creation.blocked-regions").stream().map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        this.overworldOnly = config.getBoolean("towns.creation.overworld-only", true);
        this.creationConfirmRequired = config.getBoolean("towns.creation.confirm-required", true);
        this.heartAllowExistingTownPlacementForTests = config.getBoolean("towns.heart.allow-existing-town-placement-for-tests", true);
        this.defaultNameTemplate = config.getString("towns.naming.default-template", "Town {number}");
        this.playerTownNoTownValue = config.getString("towns.placeholders.player-town.no-town-value", "-");
        this.maxNameLength = Math.max(3, Math.min(64, config.getInt("towns.naming.max-length", 16)));
        this.startingGrowthPoints = Math.max(0, config.getInt("towns.growth.starting-points", 0));
        this.growthSyncEnabled = config.getBoolean("towns.growth.sync.enabled", true);
        this.growthSyncIntervalTicks = Math.max(20, config.getInt("towns.growth.sync.interval-ticks", 1200));
        this.maxMembers = Math.max(1, config.getInt("towns.coop.max-members", 5));
        this.dynamicMemberLimitEnabled = config.getBoolean("towns.coop.dynamic-limit.enabled", true);
        this.maximumMembers = Math.max(this.maxMembers, config.getInt("towns.coop.dynamic-limit.maximum-members", 10));
        this.memberLimitCacheSeconds = Math.max(1, config.getInt("towns.coop.dynamic-limit.cache-seconds", 300));
        this.memberLimitLookupTimeoutMillis = Math.max(250, Math.min(5000, config.getInt("towns.coop.dynamic-limit.lookup-timeout-ms", 750)));
        this.vipPermission = config.getString("towns.coop.dynamic-limit.permissions.vip", "nte.vip");
        this.svipPermission = config.getString("towns.coop.dynamic-limit.permissions.svip", "nte.svip");
        this.elitePermission = config.getString("towns.coop.dynamic-limit.permissions.elite", "nte.elita");
        this.elitePermissions = loadPermissionAliases(config, "towns.coop.dynamic-limit.permissions.elite-aliases", this.elitePermission, "nte.media");
        this.vipMemberBonus = Math.max(0, config.getInt("towns.coop.dynamic-limit.bonuses.vip", 1));
        this.svipMemberBonus = Math.max(0, config.getInt("towns.coop.dynamic-limit.bonuses.svip", 1));
        this.eliteMemberBonus = Math.max(0, config.getInt("towns.coop.dynamic-limit.bonuses.elite", 2));
        this.requestTtlSeconds = Math.max(60, config.getInt("towns.coop.request-ttl-seconds", 600));
        this.confirmWindowSeconds = Math.max(5, config.getInt("towns.destroy.confirm-window-seconds", 30));
        this.visualRadiusChunks = Math.max(1, config.getInt("towns.visual-check.radius-chunks", 6));
        this.visualRefreshTicks = Math.max(20, config.getInt("towns.visual-check.refresh-ticks", 40));
        this.visualStateRefreshTicks = Math.max(0, config.getInt("towns.visual-check.state-refresh-ticks", 200));
        this.visualPillarHeight = Math.max(6, config.getInt("towns.visual-check.pillar-height", 18));
        this.visualPillarStep = Math.max(1, config.getInt("towns.visual-check.pillar-step", 4));
        this.visualEdgeStep = Math.max(1, config.getInt("towns.visual-check.edge-step", 4));
        this.visualExtendToWorldMin = config.getBoolean("towns.visual-check.extend-to-world-min", true);
        this.visualVerticalEdgeWalls = config.getBoolean("towns.visual-check.vertical-edge-walls", true);
        this.visualShowTopFrame = config.getBoolean("towns.visual-check.show-top-frame", false);
        this.visualMaxBlocksPerTickGlobal = Math.max(50, config.getInt("towns.visual-check.max-block-displays-per-tick-global", config.getInt("towns.visual-check.max-blocks-per-tick-global", 2000)));
        this.visualDisplayWidth = clampFloat((float) config.getDouble("towns.visual-check.display-width", 0.15D), 0.05F, 1.0F);
        this.visualEdgeThickness = clampFloat((float) config.getDouble("towns.visual-check.edge-thickness", 0.15D), 0.05F, 1.0F);
        this.visualDisplayViewRange = clampFloat((float) config.getDouble("towns.visual-check.display-view-range", 64.0D), 8.0F, 256.0F);
        this.mapRadiusChunks = Math.max(4, Math.min(24, config.getInt("towns.map.radius-chunks", 8)));
        this.mapCooldownSeconds = Math.max(0, config.getInt("towns.map.cooldown-seconds", 30));
        this.mapPreventDuplicates = config.getBoolean("towns.map.prevent-duplicates", true);
        this.bucketSize = Math.max(4, config.getInt("towns.scale.distance-check-bucket-size", 16));
        this.protectionBlockPlace = config.getBoolean("towns.protection.block-place", true);
        this.protectionBlockBreak = config.getBoolean("towns.protection.block-break", true);
        this.protectionInteractContainers = config.getBoolean("towns.protection.interact-containers", true);
        this.protectionInteractDoors = config.getBoolean("towns.protection.interact-doors", false);
        this.protectionInteractSwitches = config.getBoolean("towns.protection.interact-switches", false);
        this.protectionPvp = config.getBoolean("towns.protection.pvp", false);
        this.protectionExplosion = config.getBoolean("towns.protection.explosion", true);
        // New key has clear semantics. If an old server config only has fire-spread,
        // preserve its historic meaning (true = allow) instead of silently reversing it.
        this.protectionAllowFireSpread = config.contains("towns.protection.allow-fire-spread")
                ? config.getBoolean("towns.protection.allow-fire-spread", false)
                : config.getBoolean("towns.protection.fire-spread", true);
        // Existing server configs do not have this key, so default true intentionally enables
        // member/owner flint-and-steel style ignition without enabling uncontrolled fire spread.
        this.protectionAllowMemberIgnite = config.getBoolean("towns.protection.allow-member-ignite", true);
        this.protectionMobs = config.getBoolean("towns.protection.mobs", true);
        this.itemPickupWindowSeconds = Math.max(0, config.getInt("towns.protection.item-pickup-window-seconds", 60));
        this.mobBlockChangesEnabled = config.getBoolean("towns.protection.mob-block-changes.enabled", true);
        this.blockedMobBlockChangeEntities = Set.copyOf(config.getStringList("towns.protection.mob-block-changes.blocked-entities").stream()
                .map(String::trim).filter(v -> !v.isBlank()).map(String::toUpperCase).collect(java.util.stream.Collectors.toSet()));
        this.leaveResetMode = PlayerResetMode.parse(config.getString("towns.lifecycle.player-reset.leave"), PlayerResetMode.FULL);
        this.kickResetMode = PlayerResetMode.parse(config.getString("towns.lifecycle.player-reset.kick"), PlayerResetMode.TOWN_BOUND_ONLY);
        this.destroyResetMode = PlayerResetMode.parse(config.getString("towns.lifecycle.player-reset.destroy"), PlayerResetMode.FULL);
        this.coopRequestPurgeIntervalSeconds = Math.max(60, config.getInt("towns.scale.coop-request-purge-interval-seconds", 600));
        this.cleanupRetryIntervalTicks = Math.max(20L, config.getLong("towns.cleanup.retry-interval-ticks", 200L));
        this.visualBlock = Material.matchMaterial(config.getString("towns.visual-check.block", "LIME_STAINED_GLASS")) == null
                ? Material.LIME_STAINED_GLASS
                : Material.matchMaterial(config.getString("towns.visual-check.block", "LIME_STAINED_GLASS"));
    }

    public static TownsConfig load(FileConfiguration config) {
        return new TownsConfig(config);
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Set<String> loadPermissionAliases(FileConfiguration config, String path, String primary, String... requiredAliases) {
        Set<String> permissions = new HashSet<>();
        if (primary != null && !primary.isBlank()) permissions.add(primary.trim());
        for (String alias : config.getStringList(path)) {
            if (alias != null && !alias.isBlank()) permissions.add(alias.trim());
        }
        if (requiredAliases != null) {
            for (String alias : requiredAliases) {
                if (alias != null && !alias.isBlank()) permissions.add(alias.trim());
            }
        }
        return Set.copyOf(permissions);
    }


    private static List<BlockedArea> loadBlockedCreationAreas(FileConfiguration config) {
        List<BlockedArea> areas = new ArrayList<>();
        areas.addAll(loadBlockedCreationCuboids(config));

        // Legacy entries are still accepted exactly as before and intentionally have no
        // implicit buffer. This keeps existing server configs backwards compatible.
        List<?> rawAreas = config.getList("towns.creation.blocked-areas", List.of());
        for (Object raw : rawAreas) {
            BlockedArea area = parseBlockedArea(raw);
            if (area != null) areas.add(area);
        }
        return areas;
    }

    private static List<BlockedArea> loadBlockedCreationCuboids(FileConfiguration config) {
        ConfigurationSection root = config.getConfigurationSection("towns.creation.blocked-cuboids");
        if (root == null) return List.of();

        int defaultBuffer = Math.max(0, root.getInt("buffer-blocks", 0));
        ConfigurationSection regions = root.getConfigurationSection("regions");
        if (regions == null) return List.of();

        List<BlockedArea> areas = new ArrayList<>();
        for (String id : regions.getKeys(false)) {
            ConfigurationSection section = regions.getConfigurationSection(id);
            if (section == null || !section.getBoolean("enabled", true)) continue;

            if (!section.contains("min-x") || !section.contains("max-x")
                    || !section.contains("min-z") || !section.contains("max-z")) {
                continue;
            }

            String world = section.getString("world", "");
            String description = section.getString("description", id);
            int buffer = Math.max(0, section.getInt("buffer-blocks", defaultBuffer));
            areas.add(area(
                    id, description, world,
                    section.getInt("min-x"), section.getInt("min-z"),
                    section.getInt("max-x"), section.getInt("max-z"),
                    buffer
            ));
        }
        return areas;
    }

    private static BlockedArea parseBlockedArea(Object raw) {
        if (raw instanceof List<?> list) {
            if (list.size() >= 4 && allNumbers(list, 0, 4)) {
                return area("", "", "", toInt(list.get(0)), toInt(list.get(1)), toInt(list.get(2)), toInt(list.get(3)), 0);
            }
            if (list.size() >= 5 && list.get(0) != null && allNumbers(list, 1, 5)) {
                return area("", "", String.valueOf(list.get(0)), toInt(list.get(1)), toInt(list.get(2)), toInt(list.get(3)), toInt(list.get(4)), 0);
            }
        }
        if (raw instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) return null;
            String world = "";
            String coords = trimmed;
            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                world = trimmed.substring(0, colon).trim();
                coords = trimmed.substring(colon + 1).trim();
            }
            coords = coords.replace("[", "").replace("]", "");
            String[] parts = coords.split(",");
            if (parts.length >= 4) {
                try {
                    return area("", "", world, Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()), 0);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean allNumbers(List<?> values, int from, int to) {
        for (int i = from; i < to; i++) if (!(values.get(i) instanceof Number)) return false;
        return true;
    }

    private static int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static BlockedArea area(String id, String description, String world, int x1, int z1, int x2, int z2, int bufferBlocks) {
        return new BlockedArea(
                id == null ? "" : id.trim(),
                description == null ? "" : description.trim(),
                world == null ? "" : world.trim(),
                Math.min(x1, x2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(z1, z2),
                Math.max(0, bufferBlocks)
        );
    }

    public boolean isWorldAllowed(World world) {
        if (world == null) return false;
        if (overworldOnly && world.getEnvironment() != World.Environment.NORMAL) return false;
        return isWorldAllowed(world.getName());
    }

    public boolean isWorldAllowed(String worldName) {
        return worldName != null && (worldWhitelist.isEmpty() || worldWhitelist.contains(worldName));
    }

    public boolean isCreationBlocked(String worldName, int chunkX, int chunkZ) {
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        for (BlockedArea area : blockedCreationAreas) {
            if (area.intersects(worldName, minX, minZ, maxX, maxZ)) return true;
        }
        return false;
    }

    public List<String> worldWhitelist() { return List.copyOf(worldWhitelist); }
    public int initialRadius() { return initialRadius; }
    public int maxChunks() { return maxChunks; }
    public boolean dynamicChunkLimitEnabled() { return dynamicChunkLimitEnabled; }
    public int maximumChunks() { return maximumChunks; }
    public int chunkLimitCacheSeconds() { return chunkLimitCacheSeconds; }
    public String chunkVipPermission() { return chunkVipPermission; }
    public String chunkSvipPermission() { return chunkSvipPermission; }
    public String chunkElitePermission() { return chunkElitePermission; }
    public Set<String> chunkElitePermissions() { return chunkElitePermissions; }
    public int chunkVipBonus() { return chunkVipBonus; }
    public int chunkSvipBonus() { return chunkSvipBonus; }
    public int chunkEliteBonus() { return chunkEliteBonus; }
    public boolean townShapeEnabled() { return townShapeEnabled; }
    public int baseMaxWidth() { return baseMaxWidth; }
    public int baseMaxHeight() { return baseMaxHeight; }
    public int expandedThresholdChunks() { return expandedThresholdChunks; }
    public int expandedMaxWidth() { return expandedMaxWidth; }
    public int expandedMaxHeight() { return expandedMaxHeight; }
    public int bufferChunks() { return bufferChunks; }
    public int minDistanceChunks() { return minDistanceChunks; }
    public int outsiderBuildBufferChunks() { return outsiderBuildBufferChunks; }
    public List<BlockedArea> blockedCreationAreas() { return blockedCreationAreas; }
    public List<String> blockedCreationRegions() { return blockedCreationRegions; }
    public boolean overworldOnly() { return overworldOnly; }
    public boolean creationConfirmRequired() { return creationConfirmRequired; }
    public boolean heartAllowExistingTownPlacementForTests() { return heartAllowExistingTownPlacementForTests; }
    public String defaultNameTemplate() { return defaultNameTemplate; }
    public String playerTownNoTownValue() { return playerTownNoTownValue; }
    public int maxNameLength() { return maxNameLength; }
    public int startingGrowthPoints() { return startingGrowthPoints; }
    public boolean growthSyncEnabled() { return growthSyncEnabled; }
    public int growthSyncIntervalTicks() { return growthSyncIntervalTicks; }
    public int maxMembers() { return maxMembers; }
    public boolean dynamicMemberLimitEnabled() { return dynamicMemberLimitEnabled; }
    public int maximumMembers() { return maximumMembers; }
    public int memberLimitCacheSeconds() { return memberLimitCacheSeconds; }
    public int memberLimitLookupTimeoutMillis() { return memberLimitLookupTimeoutMillis; }
    public String vipPermission() { return vipPermission; }
    public String svipPermission() { return svipPermission; }
    public String elitePermission() { return elitePermission; }
    public Set<String> elitePermissions() { return elitePermissions; }
    public int vipMemberBonus() { return vipMemberBonus; }
    public int svipMemberBonus() { return svipMemberBonus; }
    public int eliteMemberBonus() { return eliteMemberBonus; }
    public int requestTtlSeconds() { return requestTtlSeconds; }
    public int confirmWindowSeconds() { return confirmWindowSeconds; }
    public int visualRadiusChunks() { return visualRadiusChunks; }
    public int visualRefreshTicks() { return visualRefreshTicks; }
    public int visualStateRefreshTicks() { return visualStateRefreshTicks; }
    public int visualPillarHeight() { return visualPillarHeight; }
    public int visualPillarStep() { return visualPillarStep; }
    public int visualEdgeStep() { return visualEdgeStep; }
    public boolean visualExtendToWorldMin() { return visualExtendToWorldMin; }
    public boolean visualVerticalEdgeWalls() { return visualVerticalEdgeWalls; }
    public boolean visualShowTopFrame() { return visualShowTopFrame; }
    public int visualMaxBlocksPerTickGlobal() { return visualMaxBlocksPerTickGlobal; }
    public float visualDisplayWidth() { return visualDisplayWidth; }
    public float visualEdgeThickness() { return visualEdgeThickness; }
    public float visualDisplayViewRange() { return visualDisplayViewRange; }
    public int mapRadiusChunks() { return mapRadiusChunks; }
    public int mapCooldownSeconds() { return mapCooldownSeconds; }
    public boolean mapPreventDuplicates() { return mapPreventDuplicates; }
    public int bucketSize() { return bucketSize; }
    public boolean protectionBlockPlace() { return protectionBlockPlace; }
    public boolean protectionBlockBreak() { return protectionBlockBreak; }
    public boolean protectionInteractContainers() { return protectionInteractContainers; }
    public boolean protectionInteractDoors() { return protectionInteractDoors; }
    public boolean protectionInteractSwitches() { return protectionInteractSwitches; }
    public boolean protectionPvp() { return protectionPvp; }
    public boolean protectionExplosion() { return protectionExplosion; }
    public boolean protectionAllowFireSpread() { return protectionAllowFireSpread; }
    public boolean protectionAllowMemberIgnite() { return protectionAllowMemberIgnite; }
    /** Compatibility alias: true means fire is allowed, matching the historic key. */
    public boolean protectionFireSpread() { return protectionAllowFireSpread; }
    public boolean protectionMobs() { return protectionMobs; }
    public int itemPickupWindowSeconds() { return itemPickupWindowSeconds; }
    public boolean mobBlockChangesEnabled() { return mobBlockChangesEnabled; }
    public Set<String> blockedMobBlockChangeEntities() { return blockedMobBlockChangeEntities; }
    public PlayerResetMode leaveResetMode() { return leaveResetMode; }
    public PlayerResetMode kickResetMode() { return kickResetMode; }
    public PlayerResetMode destroyResetMode() { return destroyResetMode; }
    public int coopRequestPurgeIntervalSeconds() { return coopRequestPurgeIntervalSeconds; }
    public long cleanupRetryIntervalTicks() { return cleanupRetryIntervalTicks; }
    public Material visualBlock() { return visualBlock; }

    public record BlockedArea(String id, String description, String world, int minX, int minZ, int maxX, int maxZ, int bufferBlocks) {
        public boolean intersects(String worldName, int otherMinX, int otherMinZ, int otherMaxX, int otherMaxZ) {
            if (world != null && !world.isBlank() && !world.equalsIgnoreCase(worldName == null ? "" : worldName)) return false;

            long distanceX = 0L;
            if ((long) otherMaxX < minX) {
                distanceX = (long) minX - otherMaxX;
            } else if ((long) otherMinX > maxX) {
                distanceX = (long) otherMinX - maxX;
            }

            long distanceZ = 0L;
            if ((long) otherMaxZ < minZ) {
                distanceZ = (long) minZ - otherMaxZ;
            } else if ((long) otherMinZ > maxZ) {
                distanceZ = (long) otherMinZ - maxZ;
            }

            long buffer = bufferBlocks;
            return distanceX * distanceX + distanceZ * distanceZ <= buffer * buffer;
        }
    }
}
