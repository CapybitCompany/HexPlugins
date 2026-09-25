package hexposterunki.rewards;

import hexposterunki.persistence.RewardClaim;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * In-process memory of frozen entitlements and a guard so two delivery paths (join handler and
 * periodic sweep) never race for the same claim inside one session.
 *
 * <p>Freezing is not a lock on persistence. The ledger only remembers <i>what</i> was frozen for a
 * run, so a repeated completion or a retried write reuses exactly the same claims. Whether they are
 * durable is decided by the consistent snapshot transaction, which retries until it succeeds; a
 * failed write therefore never blocks the claims from being stored later.
 *
 * <p>The durable guarantee is the {@code posterunki_reward_claims} primary key plus the
 * {@code CLAIMED -> DELIVERING} row-locked hand-over.
 */
public final class RewardLedger {

    private final Map<String, FrozenRewards> frozenRuns = new LinkedHashMap<>();
    private final Set<String> deliveriesInFlight = new LinkedHashSet<>();

    /**
     * @return the rewards frozen for this run; computed by {@code freezer} only the first time
     */
    public synchronized FrozenRewards freeze(String runId, Supplier<FrozenRewards> freezer) {
        return frozenRuns.computeIfAbsent(runId, ignored -> freezer.get());
    }

    public synchronized Optional<FrozenRewards> frozen(String runId) {
        return Optional.ofNullable(frozenRuns.get(runId));
    }

    public synchronized boolean isFrozen(String runId) {
        return frozenRuns.containsKey(runId);
    }

    /** @return true when the caller owns the delivery attempt for this claim right now. */
    public synchronized boolean tryDeliver(String runId, UUID playerId, int place) {
        return deliveriesInFlight.add(RewardClaim.key(runId, playerId, place));
    }

    public synchronized void releaseDelivery(String runId, UUID playerId, int place) {
        deliveriesInFlight.remove(RewardClaim.key(runId, playerId, place));
    }

    public synchronized boolean isDelivering(String runId, UUID playerId, int place) {
        return deliveriesInFlight.contains(RewardClaim.key(runId, playerId, place));
    }

    public synchronized int inFlightCount() {
        return deliveriesInFlight.size();
    }

    public synchronized void clear() {
        frozenRuns.clear();
        deliveriesInFlight.clear();
    }
}
