package hexpvpsmp.protection;

import hexpvpsmp.region.ProtectedRegion;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Aggregates one or more {@link ProtectionProvider}s. Always queries every
 * provider — the union of regions is what counts. Multiworld-aware via
 * {@link Location#getWorld()}.
 */
public final class ProtectionService {

    private final List<ProtectionProvider> providers;

    public ProtectionService(List<ProtectionProvider> providers) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
    }

    public List<ProtectedRegion> regionsAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return List.of();
        }
        return regionsAt(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }

    public List<ProtectedRegion> regionsAt(String worldName, double x, double y, double z) {
        List<ProtectedRegion> out = new ArrayList<>();
        for (ProtectionProvider provider : providers) {
            if (!provider.isAvailable()) {
                continue;
            }
            out.addAll(provider.regionsAt(worldName, x, y, z));
        }
        return out;
    }

    public boolean isInSafezone(Location location) {
        return !regionsAt(location).isEmpty();
    }

    public boolean isInSafezone(String worldName, double x, double y, double z) {
        return !regionsAt(worldName, x, y, z).isEmpty();
    }

    public List<ProtectedRegion> allRegions() {
        List<ProtectedRegion> out = new ArrayList<>();
        for (ProtectionProvider provider : providers) {
            if (!provider.isAvailable()) {
                continue;
            }
            out.addAll(provider.allRegions());
        }
        return out;
    }
}
