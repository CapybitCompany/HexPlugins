package hexpvpsmp.protection;

import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.config.WorldConfig;
import hexpvpsmp.region.PublicChest;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Explicit allowlist of container blocks players may use inside spawn.
 * Reads the config supplier live so reloads are reflected. Kept separate from
 * the region-based {@link ProtectionService} on purpose: public chests are a
 * targeted per-block allowlist, not an area — this keeps spawn protection from
 * becoming leaky through a blanket interact bypass.
 */
public final class PublicChestRegistry {

    private final Supplier<HexPvpConfig> configSupplier;

    public PublicChestRegistry(Supplier<HexPvpConfig> configSupplier) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public boolean isPublicChest(String worldName, int x, int y, int z) {
        HexPvpConfig config = configSupplier.get();
        if (config == null || worldName == null) {
            return false;
        }
        WorldConfig world = config.world(worldName.toLowerCase(Locale.ROOT)).orElse(null);
        if (world == null || !world.enabled()) {
            // A disabled world has no active protection; its chests aren't special.
            return false;
        }
        for (PublicChest chest : world.publicChests()) {
            if (chest.matches(worldName, x, y, z)) {
                return true;
            }
        }
        return false;
    }
}
