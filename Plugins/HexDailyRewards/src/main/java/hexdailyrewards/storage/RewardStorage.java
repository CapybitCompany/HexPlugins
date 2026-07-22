package hexdailyrewards.storage;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface RewardStorage {

    String DEFAULT_GROUP_ID = "default";

    Optional<LocalDate> lastClaimDate(UUID playerId, String groupId);

    default Optional<LocalDate> lastClaimDate(UUID playerId) {
        return lastClaimDate(playerId, DEFAULT_GROUP_ID);
    }

    void markClaimed(UUID playerId, String playerName, String groupId, LocalDate claimDate, Instant claimedAt) throws IOException;

    default void markClaimed(UUID playerId, String playerName, LocalDate claimDate, Instant claimedAt) throws IOException {
        markClaimed(playerId, playerName, DEFAULT_GROUP_ID, claimDate, claimedAt);
    }
}
