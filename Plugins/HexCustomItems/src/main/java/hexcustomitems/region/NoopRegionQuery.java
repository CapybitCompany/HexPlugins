package hexcustomitems.region;

import org.bukkit.Location;

import java.util.Optional;

/** Fallback ohne Region-Backend: liefert nie eine Entscheidung. */
public final class NoopRegionQuery implements RegionQuery {

    @Override
    public Optional<Boolean> offensiveAllowed(Location location) {
        return Optional.empty();
    }
}
