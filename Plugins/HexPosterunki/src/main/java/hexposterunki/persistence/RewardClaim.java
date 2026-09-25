package hexposterunki.persistence;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A durable reward entitlement, frozen at the moment of victory.
 *
 * <p>The payload is stored with the claim, so later configuration changes never alter what an
 * already earned place is worth. {@code progress} is the number of payload components that were
 * confirmed delivered, which lets a partial delivery resume instead of starting over.
 */
public record RewardClaim(
        String runId,
        String outpostId,
        UUID playerId,
        int place,
        Status status,
        int attempts,
        int progress,
        String payload,
        String lastError
) {

    public RewardClaim {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(playerId, "playerId");
        status = status == null ? Status.CLAIMED : status;
        attempts = Math.max(0, attempts);
        progress = Math.max(0, progress);
        payload = payload == null ? "" : payload;
    }

    public String key() {
        return key(runId, playerId, place);
    }

    public static String key(String runId, UUID playerId, int place) {
        return runId + ":" + playerId + ":" + place;
    }

    public enum Status {

        /** Earned and persisted, nothing handed over yet. Safe to (re)deliver. */
        CLAIMED("oczekuje na odbiór"),

        /**
         * A delivery attempt is running right now. If the server dies in this state the outcome is
         * unknown, because the database and the Minecraft world are not one transaction.
         */
        DELIVERING("w trakcie wydawania"),

        /** Fully handed over. */
        DELIVERED("wydana"),

        /**
         * The delivery outcome could not be established (crash mid-delivery, or an executor threw
         * after partially running). Never retried automatically - an admin decides.
         */
        NEEDS_REVIEW("wymaga decyzji administratora");

        private final String polishLabel;

        Status(String polishLabel) {
            this.polishLabel = polishLabel;
        }

        public String polishLabel() {
            return polishLabel;
        }

        public static Status parse(String raw, Status fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return Status.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }
}
