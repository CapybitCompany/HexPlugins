package hexpvpsmp.ui;

import hexpvpsmp.combat.CombatTagService;
import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.util.LegacyFormat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Single repeating task that:
 *  - sweeps expired combat tags
 *  - sends the "Combat: Xs" actionbar to currently tagged players (if enabled)
 *
 * No per-player tasks.
 */
public final class ActionBarService {

    private final Plugin plugin;
    private final CombatTagService combatTagService;
    private final MessageService messageService;
    private final Supplier<HexPvpConfig> configSupplier;
    private BukkitTask task;

    public ActionBarService(Plugin plugin,
                            CombatTagService combatTagService,
                            MessageService messageService,
                            Supplier<HexPvpConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.combatTagService = Objects.requireNonNull(combatTagService, "combatTagService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public void start() {
        if (task != null) {
            return;
        }
        int interval = Math.max(1, configSupplier.get().combat().actionbarUpdateTicks());
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        long now = plugin.getServer().getCurrentTick();
        combatTagService.expire(now);

        HexPvpConfig config = configSupplier.get();
        if (config == null || !config.enabled() || !config.combat().actionbarEnabled()) {
            return;
        }

        for (UUID playerId : combatTagService.snapshot().keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            int remaining = combatTagService.remainingSeconds(playerId);
            if (remaining <= 0) {
                continue;
            }
            String text = LegacyFormat.replace(
                    config.messages().combatActionbar(), "<seconds>", Integer.toString(remaining));
            messageService.sendActionBarUnthrottled(player, text);
        }
    }
}
