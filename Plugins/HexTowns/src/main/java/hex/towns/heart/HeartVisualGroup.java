package hex.towns.heart;

import java.util.UUID;

public record HeartVisualGroup(
        UUID townId,
        String rawTownId,
        HeartVisualStatus status,
        String reason,
        String world,
        double x,
        double y,
        double z,
        int entityCount,
        int removedEntities
) {
}
