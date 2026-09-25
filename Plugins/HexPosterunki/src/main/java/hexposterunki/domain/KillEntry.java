package hexposterunki.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Per-player kill progress inside the current run.
 *
 * @param achievedAtMillis instant at which the current {@code kills} value was reached; it is the
 *                         second ranking criterion, so an earlier finisher beats a later one.
 */
public record KillEntry(UUID playerId, UUID townId, int kills, long achievedAtMillis) {

    public KillEntry {
        Objects.requireNonNull(playerId, "playerId");
        kills = Math.max(0, kills);
    }

    public KillEntry withKill(UUID currentTownId, long nowMillis) {
        return new KillEntry(playerId, currentTownId, kills + 1, nowMillis);
    }
}
