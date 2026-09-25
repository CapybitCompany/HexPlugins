package hexposterunki.listener;

import hex.core.api.ui.UiTokens;
import hexposterunki.engine.OutpostEngine;
import hexposterunki.rewards.RewardService;
import hexposterunki.ui.PosterunkiUi;
import hexposterunki.ui.UiKeys;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Translates player movement, teleports, deaths and disconnects into engine calls.
 *
 * <p>Progress is lost on death, on leaving the region, on a normal logout, on a world change and
 * on teleporting out. A server shutdown is explicitly not treated as a voluntary leave - the
 * shutdown flag is supplied by the plugin.
 */
public final class PlayerTrackingListener implements Listener {

    private final OutpostEngine engine;
    private final PosterunkiUi ui;
    private final RewardService rewards;
    private final BooleanSupplier shuttingDown;

    public PlayerTrackingListener(OutpostEngine engine, PosterunkiUi ui, RewardService rewards,
                                  BooleanSupplier shuttingDown) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.rewards = Objects.requireNonNull(rewards, "rewards");
        this.shuttingDown = Objects.requireNonNull(shuttingDown, "shuttingDown");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        boolean wasInside = engine.participation().isInside(event.getPlayer().getUniqueId());
        boolean isInside = engine.activeRegionContains(to);
        if (isInside && !wasInside) {
            engine.onEnterRegion(event.getPlayer());
        } else if (!isInside && wasInside) {
            engine.onLeaveRegion(event.getPlayer(), UiKeys.PROGRESS_LOST_LEAVE, "leave-region");
        }
    }

    /**
     * Teleporting into the active outpost region is blocked for normal players - commands, ender
     * pearls, chorus fruit and plugin teleports alike. Runs late, so a destination another plugin
     * changed before is judged as it will actually be used.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleportEntry(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(RegionProtectionListener.BYPASS)) {
            return;
        }
        if (engine.activeRegionContains(event.getTo()) && !engine.activeRegionContains(event.getFrom())) {
            event.setCancelled(true);
            ui.send(player, UiKeys.TELEPORT_BLOCKED, new UiTokens());
        }
    }

    /**
     * Teleporting out is allowed but resets progress - only once the teleport is final: not cancelled
     * by any plugin, and judged by its final destination. A teleport cancelled at a later priority
     * leaves the player inside with all kills.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportCompleted(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (engine.activeRegionContains(event.getFrom()) && !engine.activeRegionContains(to)) {
            engine.onLeaveRegion(event.getPlayer(), UiKeys.PROGRESS_LOST_TELEPORT, "teleport-out");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        engine.participation().markOutside(player.getUniqueId());
        engine.loseProgress(player, UiKeys.PROGRESS_LOST_DEATH, "death");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!engine.participation().markOutside(player.getUniqueId())) {
            return;
        }
        engine.loseProgress(player, UiKeys.PROGRESS_LOST_WORLD, "world-change");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        engine.onQuit(event.getPlayer(), shuttingDown.getAsBoolean());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // A player who reconnects inside the region is registered again, but starts from zero:
        // the logout already cleared the kill progress.
        if (engine.activeRegionContains(event.getPlayer().getLocation())) {
            engine.onEnterRegion(event.getPlayer());
        }
        // A reward earned while the player was offline is handed over now.
        rewards.deliverFor(event.getPlayer());
    }
}
