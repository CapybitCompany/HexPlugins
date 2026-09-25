package hexposterunki.rewards;

import org.bukkit.entity.Player;

import java.util.List;

/** Dispatches a component to the executor that supports its type. */
public final class CompositeRewardExecutor implements RewardExecutor {

    private final List<RewardExecutor> delegates;

    public CompositeRewardExecutor(List<RewardExecutor> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    public static CompositeRewardExecutor standard() {
        return new CompositeRewardExecutor(List.of(new ItemRewardExecutor(), new CommandRewardExecutor()));
    }

    @Override
    public String id() {
        return "composite";
    }

    @Override
    public boolean supports(RewardComponent.Type type) {
        return delegates.stream().anyMatch(delegate -> delegate.supports(type));
    }

    @Override
    public DeliveryOutcome deliver(Player player, RewardComponent component, String payoutId) {
        for (RewardExecutor delegate : delegates) {
            if (delegate.supports(component.type())) {
                return delegate.deliver(player, component, payoutId);
            }
        }
        return DeliveryOutcome.notExecuted("brak wykonawcy dla typu " + component.type());
    }
}
