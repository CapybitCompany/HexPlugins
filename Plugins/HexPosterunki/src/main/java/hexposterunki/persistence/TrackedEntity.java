package hexposterunki.persistence;

import java.util.UUID;

/**
 * A persisted entity belonging to a run, used by recovery to match world entities against the
 * database and to remove duplicates.
 */
public record TrackedEntity(UUID entityId, Kind kind, String mobId, int wave) {

    public enum Kind {
        MOB,
        BOSS
    }
}
