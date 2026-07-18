package hexpvpsmp.movement;

import hexpvpsmp.HexPvpSmpPlugin;
import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.config.MessagesConfig;
import hexpvpsmp.protection.ProtectionService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Shows safezone entry/exit information as a title + subtitle (never a bossbar):
 * on entering the spawn safezone the entry title/subtitle, on leaving the exit
 * title/subtitle. A per-player cooldown ({@code safezones.info-cooldown-ticks})
 * stops the messages spamming when a player skims the boundary.
 */
public final class SafezoneInfoListener implements Listener {

    /** Kind of safezone boundary crossing. */
    public enum Transition {
        ENTER, EXIT, NONE
    }

    private final HexPvpSmpPlugin plugin;
    private final Map<UUID, Boolean> insideState = new HashMap<>();
    private final Map<UUID, Long> lastInfoTick = new HashMap<>();

    public SafezoneInfoListener(HexPvpSmpPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** Pure transition classifier — visible for testing. */
    public static Transition classify(boolean wasInside, boolean nowInside) {
        if (wasInside == nowInside) {
            return Transition.NONE;
        }
        return nowInside ? Transition.ENTER : Transition.EXIT;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || sameBlock(from, to)) {
            return;
        }
        HexPvpConfig config = plugin.config();
        if (config == null || !config.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        ProtectionService protection = plugin.protectionService();
        if (protection == null) {
            return;
        }
        UUID id = player.getUniqueId();
        boolean nowInside = protection.isSpawnSafezone(to);
        // With no prior sample, derive the previous state from the move's origin
        // so the very first border crossing (e.g. login just outside, first step
        // in) still fires the correct entry/exit info.
        boolean fromInside = protection.isSpawnSafezone(from);
        Transition transition = decide(id, fromInside, nowInside,
                plugin.getServer().getCurrentTick(), config.safezones().infoCooldownTicks());
        if (transition == Transition.NONE) {
            return;
        }
        MessagesConfig messages = config.messages();
        if (transition == Transition.ENTER) {
            plugin.messageService().showTitle(player,
                    messages.safezoneEnterTitle(), messages.safezoneEnterSubtitle());
        } else {
            plugin.messageService().showTitle(player,
                    messages.safezoneExitTitle(), messages.safezoneExitSubtitle());
        }
    }

    /**
     * Stateful transition decision, visible for testing. Updates the per-player
     * inside-state and cooldown and returns the transition that should be shown
     * ({@link Transition#NONE} if there is nothing to show or the cooldown is
     * still active). On the first sample for a player the {@code fromInside}
     * argument (derived from the move origin) is used as the previous state, so
     * the very first tracked border crossing still fires.
     */
    public Transition decide(UUID id, boolean fromInside, boolean toInside, long nowTick, int cooldown) {
        Boolean prev = insideState.put(id, toInside);
        boolean wasInside = prev != null ? prev : fromInside;
        Transition transition = classify(wasInside, toInside);
        if (transition == Transition.NONE) {
            return Transition.NONE;
        }
        Long last = lastInfoTick.get(id);
        if (last != null && (nowTick - last) < cooldown) {
            return Transition.NONE;
        }
        lastInfoTick.put(id, nowTick);
        return transition;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        insideState.remove(id);
        lastInfoTick.remove(id);
    }

    private boolean sameBlock(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        if (!Objects.equals(a.getWorld().getUID(), b.getWorld().getUID())) {
            return false;
        }
        return a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }
}
