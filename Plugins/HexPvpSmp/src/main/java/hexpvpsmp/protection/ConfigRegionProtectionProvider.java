package hexpvpsmp.protection;

import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.config.SpawnConfig;
import hexpvpsmp.config.WorldConfig;
import hexpvpsmp.region.ProtectedRegion;
import hexpvpsmp.region.RegionId;
import hexpvpsmp.region.RegionType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Serves spawn safezones and no-build zones defined in the plugin config.
 * Reads the supplier on each call so a config reload is reflected without
 * needing to rebuild the provider. This plugin owns all protection regions;
 * there is no external region source (no WorldGuard).
 */
public final class ConfigRegionProtectionProvider implements ProtectionProvider {

    private final Supplier<HexPvpConfig> configSupplier;

    public ConfigRegionProtectionProvider(Supplier<HexPvpConfig> configSupplier) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    @Override
    public boolean isAvailable() {
        return configSupplier.get() != null;
    }

    @Override
    public List<ProtectedRegion> regionsAt(String worldName, double x, double y, double z) {
        HexPvpConfig config = configSupplier.get();
        if (config == null || worldName == null) {
            return List.of();
        }
        String key = worldName.toLowerCase(Locale.ROOT);
        WorldConfig worldConfig = config.world(key).orElse(null);
        if (worldConfig == null || !worldConfig.enabled()) {
            return List.of();
        }

        // Regions are vertically unbounded: only X/Z decide containment.
        List<ProtectedRegion> hits = new ArrayList<>();
        SpawnConfig spawn = worldConfig.spawn();
        if (spawn.enabled() && spawn.region() != null && spawn.region().contains(x, z)) {
            hits.add(spawnRegionFor(key, spawn));
        }
        for (ProtectedRegion zone : worldConfig.noBuildZones()) {
            if (zone.cuboid().contains(x, z)) {
                hits.add(zone);
            }
        }
        return hits;
    }

    @Override
    public List<ProtectedRegion> allRegions() {
        HexPvpConfig config = configSupplier.get();
        if (config == null) {
            return List.of();
        }
        List<ProtectedRegion> all = new ArrayList<>();
        for (WorldConfig wc : config.worlds().values()) {
            if (!wc.enabled()) {
                continue;
            }
            SpawnConfig spawn = wc.spawn();
            if (spawn.enabled() && spawn.region() != null) {
                all.add(spawnRegionFor(wc.world(), spawn));
            }
            all.addAll(wc.noBuildZones());
        }
        return Collections.unmodifiableList(all);
    }

    private static ProtectedRegion spawnRegionFor(String world, SpawnConfig spawn) {
        // RegionId allowed chars + "spawn-<world>" is always valid.
        return new ProtectedRegion(
                new RegionId("spawn-" + world),
                world,
                RegionType.SPAWN_SAFEZONE,
                spawn.region()
        );
    }
}
