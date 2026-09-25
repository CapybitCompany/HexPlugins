package hexposterunki.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A player currently standing inside the outpost region.
 *
 * @param townId          town the player belongs to; never null (townless players are not tracked here)
 * @param enteredAtMillis when this player entered the region, the deterministic takeover tie-breaker
 */
public record Presence(UUID playerId, UUID townId, long enteredAtMillis) {

    public Presence {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(townId, "townId");
    }
}
