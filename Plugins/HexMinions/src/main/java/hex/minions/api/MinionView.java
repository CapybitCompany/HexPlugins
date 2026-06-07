package hex.minions.api;

import hex.minions.model.MinionState;

import java.util.Map;
import java.util.UUID;

public record MinionView(
        UUID id,
        UUID townUuid,
        String typeId,
        String displayName,
        int tier,
        String world,
        int x,
        int y,
        int z,
        MinionState state,
        int storageUsed,
        int storageLimit,
        Map<String, Long> storage
) {
}

