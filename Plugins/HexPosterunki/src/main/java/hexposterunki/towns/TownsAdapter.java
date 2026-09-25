package hexposterunki.towns;

import java.util.Optional;
import java.util.UUID;

/**
 * Everything HexPosterunki needs to know about towns. Kept tiny on purpose: the whole HexTowns
 * surface stays out of this plugin, and the implementation can be swapped for a fake in tests.
 */
public interface TownsAdapter {

    /** True when a compatible HexTowns API is bound and usable. */
    boolean available();

    /** Human readable binding status, shown by {@code /posterunki status} and logged at startup. */
    String status();

    /** Town of a player, empty when the player has no town. */
    Optional<UUID> townIdOf(UUID playerId);

    /** Display name of a town, empty when the town is unknown. */
    Optional<String> townName(UUID townId);

    /** Convenience: town display name or a stable fallback for UI. */
    default String townNameOr(UUID townId, String fallback) {
        return townId == null ? fallback : townName(townId).orElse(fallback);
    }
}
