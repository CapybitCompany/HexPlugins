package hex.towns.api;

import hex.towns.model.ChunkPos;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Durable snapshot passed to dependent plugins while a town is being destroyed.
 * Values come from the cleanup job, not from live ACTIVE-town lookups, so legacy
 * data can still be identified after the town enters DESTROYING.
 */
public record TownPurgeContext(
        UUID townUuid,
        long internalTownId,
        UUID ownerUuid,
        String worldName,
        Set<ChunkPos> chunks,
        List<UUID> members
) {
    public TownPurgeContext {
        chunks = chunks == null ? Set.of() : Set.copyOf(chunks);
        members = members == null ? List.of() : List.copyOf(members);
    }

    public static TownPurgeContext compatibility(UUID townUuid, List<UUID> members) {
        return new TownPurgeContext(townUuid, 0L, null, null, Set.of(), members);
    }
}
