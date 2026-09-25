package hexposterunki.rewards;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Runs a console command.
 *
 * <p>{@code <player>}, {@code <place>} and {@code <payout_id>} are substituted before dispatch.
 * {@code <payout_id>} is stable per claim component; a receiving plugin that supports idempotent
 * payouts can use it to reject a repeat. Without such a receiver a command that was interrupted
 * by a crash is reported as unknown rather than replayed.
 */
public final class CommandRewardExecutor implements RewardExecutor {

    @Override
    public String id() {
        return "commands";
    }

    @Override
    public boolean supports(RewardComponent.Type type) {
        return type == RewardComponent.Type.COMMAND;
    }

    @Override
    public DeliveryOutcome deliver(Player player, RewardComponent component, String payoutId) {
        String command = component.value()
                .replace("<player>", player.getName())
                .replace("<payout_id>", payoutId);
        try {
            boolean handled = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            if (handled) {
                return DeliveryOutcome.ok();
            }
            return DeliveryOutcome.notExecuted("komenda nieobsłużona: " + command);
        } catch (RuntimeException exception) {
            // The command may have had partial effect before throwing.
            return DeliveryOutcome.unknown("błąd komendy '" + command + "': " + exception.getMessage());
        }
    }
}
