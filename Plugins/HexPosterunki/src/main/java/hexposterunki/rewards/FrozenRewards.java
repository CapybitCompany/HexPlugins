package hexposterunki.rewards;

import hexposterunki.domain.KillEntry;
import hexposterunki.persistence.RewardClaim;

import java.util.List;

/**
 * Standings and reward claims of one run, frozen once at victory.
 *
 * <p>Immutable on purpose: a retried write, a later {@code config.yml} change or a second completion
 * call for the same run all see exactly these claims and payloads.
 *
 * @param ranking the ranked entries (best first) that earned a place
 * @param claims  concrete entitlements with serialised payloads; empty when rewards are disabled
 */
public record FrozenRewards(String runId, List<KillEntry> ranking, List<RewardClaim> claims) {

    public FrozenRewards {
        ranking = ranking == null ? List.of() : List.copyOf(ranking);
        claims = claims == null ? List.of() : List.copyOf(claims);
    }
}
