package hexposterunki.rewards;

import org.bukkit.entity.Player;

/**
 * Delivers one component of a reward.
 *
 * <p>Component-based on purpose: a claim records how many components were confirmed delivered, so
 * a partially delivered reward resumes with the remaining ones instead of handing out the first
 * ones twice.
 *
 * <p>Kept behind an interface so a HexCustomItems-backed executor can be added later without
 * touching ranking, claims or idempotency handling.
 */
public interface RewardExecutor {

    String id();

    boolean supports(RewardComponent.Type type);

    /**
     * Main thread.
     *
     * @param payoutId stable id of this exact component of this exact claim; pass it on to
     *                 receivers that support idempotent payouts
     */
    DeliveryOutcome deliver(Player player, RewardComponent component, String payoutId);
}
