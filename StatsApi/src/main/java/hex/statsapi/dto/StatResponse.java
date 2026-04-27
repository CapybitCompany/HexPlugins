package hex.statsapi.dto;

import java.util.List;
import java.util.UUID;

public record StatResponse(
        String statId,
        String displayName,
        List<PlayerStatEntry> entries
) {
    public record PlayerStatEntry(
            UUID uuid,
            String nickname,
            long value
    ) {
    }
}

