package hexposterunki.support;

import hexposterunki.rewards.DeliveryOutcome;
import hexposterunki.rewards.ItemRewardExecutor;
import hexposterunki.rewards.RewardComponent;
import hexposterunki.rewards.RewardExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Test double at the reward executor boundary - the place where items and console commands leave the
 * plugin. It hands real items over through the production {@link ItemRewardExecutor}, records what it
 * was asked to deliver, can fail one component in a defined way, and can run a hook right after a
 * component was handed over (used to break the database exactly between the world change and the
 * status write).
 */
public final class ScriptedRewardExecutor implements RewardExecutor {

    private final ItemRewardExecutor items = new ItemRewardExecutor();
    private final List<String> delivered = new ArrayList<>();
    private int failAtIndex = -1;
    private boolean failCertain = true;
    private Runnable afterDelivery = () -> {
    };

    /** @param certain true for a provable non-execution, false for an unknown outcome */
    public void failAt(int componentIndex, boolean certain) {
        this.failAtIndex = componentIndex;
        this.failCertain = certain;
    }

    public void afterDelivery(Runnable hook) {
        this.afterDelivery = hook;
    }

    /** Payout ids of every component actually handed over, in order. */
    public List<String> delivered() {
        return List.copyOf(delivered);
    }

    @Override
    public String id() {
        return "test";
    }

    @Override
    public boolean supports(RewardComponent.Type type) {
        return true;
    }

    @Override
    public DeliveryOutcome deliver(Player player, RewardComponent component, String payoutId) {
        if (failAtIndex >= 0 && payoutId.endsWith("#" + failAtIndex)) {
            return failCertain ? DeliveryOutcome.notExecuted("zaplanowany błąd")
                    : DeliveryOutcome.unknown("nieznany wynik");
        }
        DeliveryOutcome outcome = items.deliver(player, component, payoutId);
        if (outcome.delivered()) {
            delivered.add(payoutId);
        }
        afterDelivery.run();
        return outcome;
    }
}
