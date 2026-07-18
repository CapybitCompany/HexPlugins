package hexdailyrewards.storage;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface RewardStorage {

    Optional<LocalDate> lastClaimDate(UUID playerId);

    void markClaimed(UUID playerId, String playerName, LocalDate claimDate, Instant claimedAt) throws IOException;
}

