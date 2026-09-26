package hexpvpsmp.protection;

import hexpvpsmp.region.ProtectedRegion;

import java.util.List;

/**
 * Source of protected regions. Multiple providers may coexist;
 * {@link ProtectionService} composes them.
 */
public interface ProtectionProvider {

    boolean isAvailable();

    /**
     * Returns regions in {@code worldName} that contain the given point.
     * Implementations should be cheap to call per-move event.
     */
    List<ProtectedRegion> regionsAt(String worldName, double x, double y, double z);

    /**
     * All regions exposed by this provider, used for /hexpvp regions listings.
     * Optional — providers that cannot enumerate (e.g., live WG queries) may
     * return an empty list.
     */
    default List<ProtectedRegion> allRegions() {
        return List.of();
    }
}
