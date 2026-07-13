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
 *
 * <p>Two orthogonal protections are derived from the regions:
 * <ul>
 *   <li><b>PvP protection</b> — only {@code SPAWN_SAFEZONE} regions block PvP
 *       and combat-tagged entry.</li>
 *   <li><b>Build protection</b> — every region ({@code SPAWN_SAFEZONE} and
 *       {@code NO_BUILD}) blocks building/breaking.</li>
 * </ul>
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

    /** Regions at the location that deny PvP (spawn safezones only). */
    public List<ProtectedRegion> safezonesAt(Location location) {
        List<ProtectedRegion> out = new ArrayList<>();
        for (ProtectedRegion r : regionsAt(location)) {
            if (r.type().blocksPvp()) {
                out.add(r);
            }
        }
        return out;
    }

    /** True if PvP is denied at the location (inside a spawn safezone). */
    public boolean isPvpProtected(Location location) {
        for (ProtectedRegion r : regionsAt(location)) {
            if (r.type().blocksPvp()) {
                return true;
            }
        }
        return false;
    }

    public boolean isPvpProtected(String worldName, double x, double y, double z) {
        for (ProtectedRegion r : regionsAt(worldName, x, y, z)) {
            if (r.type().blocksPvp()) {
                return true;
            }
        }
        return false;
    }

    /** True if building/breaking is denied at the location (any region). */
    public boolean isBuildProtected(Location location) {
        return !regionsAt(location).isEmpty();
    }

    public boolean isBuildProtected(String worldName, double x, double y, double z) {
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
