package hexposterunki.persistence;

import java.util.List;
import java.util.Objects;

/**
 * The frozen result of a won run: the final standings and the concrete reward claims.
 *
 * <p>It travels inside the consistent snapshot and is written in the same transaction that stores
 * the run as {@code COMPLETED}. A restart can therefore never observe a completed run without its
 * claims, nor claims of a run that was not completed. Both writes are idempotent, so the record can
 * ride along with every later snapshot until one of them is confirmed durable.
 *
 * @param completedAtMillis frozen completion time; a retried write stores the same value
 * @param stats             final standings of every participant (place 0 = unranked)
 * @param claims            reward entitlements with their serialised payloads, frozen at victory
 */
public record CompletionRecord(
        String runId,
        String outpostId,
        long completedAtMillis,
        List<PosterunkiRepository.StatRow> stats,
        List<RewardClaim> claims
) {

    public CompletionRecord {
        Objects.requireNonNull(runId, "runId");
        outpostId = outpostId == null ? "-" : outpostId;
        stats = stats == null ? List.of() : List.copyOf(stats);
        claims = claims == null ? List.of() : List.copyOf(claims);
    }
}
