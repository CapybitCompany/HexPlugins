package hexpvpsmp.protection;

import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.config.SpawnConfig;
import hexpvpsmp.config.TownsConfig;
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
 * Serves spawn and town regions defined in the plugin config. Reads the
 * supplier on each call so a config reload is reflected without needing
 * to rebuild the provider.
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
        List<ProtectedRegion> hits = new ArrayList<>();

        WorldConfig worldConfig = config.world(key).orElse(null);
        if (worldConfig != null && worldConfig.enabled()) {
            SpawnConfig spawn = worldConfig.spawn();
            if (spawn.enabled() && spawn.region() != null && spawn.region().contains(x, y, z)) {
                hits.add(spawnRegionFor(key, spawn));
            }
        }

        if (config.towns().provider() == TownsConfig.Provider.CONFIG) {
            for (ProtectedRegion town : config.towns().regions()) {
                if (!town.world().equals(key)) {
                    continue;
                }
                if (town.cuboid().contains(x, y, z)) {
                    hits.add(town);
                }
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
            SpawnConfig spawn = wc.spawn();
            if (spawn.enabled() && spawn.region() != null) {
                all.add(spawnRegionFor(wc.world(), spawn));
            }
        }
        if (config.towns().provider() == TownsConfig.Provider.CONFIG) {
            all.addAll(config.towns().regions());
        }
        return Collections.unmodifiableList(all);
    }

    private static ProtectedRegion spawnRegionFor(String world, SpawnConfig spawn) {
        // RegionId allowed chars + "spawn-<world>" is always valid.
        return new ProtectedRegion(
                new RegionId("spawn-" + world),
                world,
                RegionType.SPAWN,
                spawn.region()
        );
    }
}
