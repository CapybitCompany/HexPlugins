package hexdailyrewards;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

public record ClaimState(
        String rewardGroupId,
        boolean available,
        LocalDate today,
        LocalDate lastClaimDate,
        Instant nextReset,
        Duration remaining
) {
}
