package hexposterunki.recovery;

import java.util.Objects;
import java.util.UUID;

/**
 * An entity found in the world that carries HexPosterunki PDC tags.
 *
 * @param runId run the entity claims to belong to
 * @param wave  wave the entity was spawned for (0 for the boss)
 * @param boss  true for the STORMBOSSY boss entity bound to a run
 */
public record FoundEntity(UUID entityId, String runId, int wave, boolean boss) {

    public FoundEntity {
        Objects.requireNonNull(entityId, "entityId");
    }
}
