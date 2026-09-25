package hexposterunki.rewards;

import hex.core.api.ui.UiTokens;
import hexposterunki.config.RewardsConfig;
import hexposterunki.domain.KillEntry;
import hexposterunki.domain.RankingCalculator;
import hexposterunki.persistence.AuditEvent;
import hexposterunki.persistence.PersistenceService;
import hexposterunki.persistence.RewardClaim;
import hexposterunki.ui.PosterunkiUi;
import hexposterunki.ui.UiKeys;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Freezes and delivers the optional top-5 rewards of a finished run.
 *
 * <p>Two clearly separated steps:
 * <ol>
 *   <li><b>Freeze.</b> On victory the ranking is turned into claims that carry their own payload.
 *       The engine stores them atomically with the completed run. Later edits to {@code config.yml}
 *       never change an earned claim, and leaving the region afterwards never removes one.</li>
 *   <li><b>Deliver.</b> A claim moves {@code CLAIMED -> DELIVERING -> DELIVERED} under a row lock.
 *       Offline players keep their claim and are served when they come back.</li>
 * </ol>
 *
 * <p>The honest limit: the database and the Minecraft world are not one transaction. A crash while
 * a claim is {@code DELIVERING} leaves an unknown outcome, so that claim is parked as
 * {@code NEEDS_REVIEW} instead of being paid again or silently dropped. Boss rewards are out of
 * scope entirely - STORMBOSSY pays those natively.
 */
public final class RewardService {

    private final Plugin plugin;
    private final PersistenceService persistence;
    private final PosterunkiUi ui;
    private final RewardExecutor executor;
    private final RewardLedger ledger = new RewardLedger();
    private int maxAttempts = 3;

    public RewardService(Plugin plugin, PersistenceService persistence, PosterunkiUi ui, RewardExecutor executor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void setMaxAttempts(int value) {
        this.maxAttempts = Math.max(1, value);
    }

    public void resetLedger() {
        ledger.clear();
    }

    public RewardLedger ledger() {
        return ledger;
    }

    /**
     * Main thread. Freezes the ranking and the concrete reward payloads of a won run - once.
     *
     * <p>Nothing is written here. The caller stores the returned claims inside the same consistent
     * snapshot that marks the run as completed (see {@code CompletionRecord}), so there is no window
     * in which a completion exists without its claims. A second call for the same run returns the
     * already frozen result, even if the configuration changed in between.
     */
    public FrozenRewards freezeClaims(String runId, String outpostId, UUID controllingTown,
                                      Collection<KillEntry> entries, int minKills, RewardsConfig config) {
        Objects.requireNonNull(runId, "runId");
        List<KillEntry> snapshot = List.copyOf(entries);
        return ledger.freeze(runId, () -> compute(runId, outpostId, controllingTown, snapshot, minKills, config));
    }

    private static FrozenRewards compute(String runId, String outpostId, UUID controllingTown,
                                         List<KillEntry> entries, int minKills, RewardsConfig config) {
        List<KillEntry> ranking = RankingCalculator.top(entries, controllingTown, minKills,
                Math.max(config.places(), 5));
        List<RewardClaim> claims = new ArrayList<>();
        if (config.enabled()) {
            for (int index = 0; index < ranking.size() && index < config.places(); index++) {
                int place = index + 1;
                RewardsConfig.PlaceReward reward = config.rewards().get(place);
                if (reward == null || reward.isEmpty()) {
                    continue;
                }
                RewardPayload payload = RewardPayload.from(reward);
                if (payload.isEmpty()) {
                    continue;
                }
                claims.add(new RewardClaim(runId, outpostId, ranking.get(index).playerId(), place,
                        RewardClaim.Status.CLAIMED, 0, 0, payload.serialize(), null));
            }
        }
        return new FrozenRewards(runId, ranking, claims);
    }

    /** Main thread. Tries to deliver every outstanding claim whose player is online. */
    public void deliverOutstanding() {
        persistence.loadClaims(RewardClaim.Status.CLAIMED).thenAccept(claims ->
                Bukkit.getScheduler().runTask(plugin, () -> claims.forEach(this::tryDeliver)));
    }

    /** Main thread. Called when a player joins, so an offline claim is served on return. */
    public void deliverFor(Player player) {
        UUID playerId = player.getUniqueId();
        persistence.loadClaimsForPlayer(playerId, RewardClaim.Status.CLAIMED).thenAccept(claims ->
                Bukkit.getScheduler().runTask(plugin, () -> claims.forEach(this::tryDeliver)));
    }

    private void tryDeliver(RewardClaim claim) {
        Player player = Bukkit.getPlayer(claim.playerId());
        if (player == null || !player.isOnline()) {
            // Keep the claim; the join handler picks it up later.
            return;
        }
        if (!ledger.tryDeliver(claim.runId(), claim.playerId(), claim.place())) {
            return;
        }
        persistence.beginDelivery(claim.key()).thenAccept(taken -> Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                taken.ifPresent(this::execute);
            } finally {
                ledger.releaseDelivery(claim.runId(), claim.playerId(), claim.place());
            }
        })).exceptionally(error -> {
            ledger.releaseDelivery(claim.runId(), claim.playerId(), claim.place());
            persistence.audit(claim.runId(), claim.outpostId(), AuditEvent.REWARD_FAILED,
                    claim.playerId().toString(), "beginDelivery: " + error.getMessage());
            return null;
        });
    }

    /** Main thread. Runs the remaining components of a claim already moved to DELIVERING. */
    private void execute(RewardClaim claim) {
        Player player = Bukkit.getPlayer(claim.playerId());
        RewardPayload payload = RewardPayload.deserialize(claim.payload());
        if (player == null || !player.isOnline()) {
            // Nothing was attempted; hand the claim straight back so it can be retried later.
            persistence.finishDelivery(claim.key(), RewardClaim.Status.CLAIMED, claim.progress(),
                    "gracz offline");
            return;
        }

        int progress = Math.min(claim.progress(), payload.size());
        String problem = null;
        boolean uncertain = false;

        for (int index = progress; index < payload.size(); index++) {
            String payoutId = claim.key() + "#" + index;
            DeliveryOutcome outcome = executor.deliver(player, payload.components().get(index), payoutId);
            if (outcome.delivered()) {
                progress = index + 1;
                continue;
            }
            problem = outcome.problem();
            uncertain = !outcome.certain();
            break;
        }

        if (problem == null) {
            persistence.finishDelivery(claim.key(), RewardClaim.Status.DELIVERED, progress, null);
            persistence.audit(claim.runId(), claim.outpostId(), AuditEvent.REWARD_GRANTED,
                    claim.playerId().toString(), "place=" + claim.place() + " components=" + progress);
            ui.send(player, UiKeys.REWARD_GRANTED, UiTokens.of("place", String.valueOf(claim.place())));
            return;
        }

        RewardClaim.Status next;
        if (uncertain) {
            // Unknown side effect - never retried automatically.
            next = RewardClaim.Status.NEEDS_REVIEW;
        } else if (claim.attempts() >= maxAttempts) {
            next = RewardClaim.Status.NEEDS_REVIEW;
        } else {
            next = RewardClaim.Status.CLAIMED;
        }
        persistence.finishDelivery(claim.key(), next, progress, problem);
        persistence.audit(claim.runId(), claim.outpostId(), AuditEvent.REWARD_FAILED,
                claim.playerId().toString(),
                "place=" + claim.place() + " progress=" + progress + " status=" + next + " problem=" + problem);
        if (next == RewardClaim.Status.NEEDS_REVIEW) {
            ui.send(player, UiKeys.REWARD_PENDING_REVIEW, new UiTokens());
        }
    }
}
