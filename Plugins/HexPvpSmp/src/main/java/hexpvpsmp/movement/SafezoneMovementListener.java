package hexpvpsmp.movement;

import hexpvpsmp.HexPvpSmpPlugin;
import hexpvpsmp.combat.CombatState;
import hexpvpsmp.combat.CombatTagService;
import hexpvpsmp.combat.PermissionGate;
import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.protection.ProtectionService;
import hexpvpsmp.region.ProtectedRegion;
import hexpvpsmp.ui.MessageService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;
import java.util.Objects;

/**
 * Stops combat-tagged players from entering safezones.
 *
 * Strategy:
 *  - Always cancel the event when the destination is inside a safezone.
 *  - For PlayerTeleportEvent additionally schedule a defensive fallback
 *    teleport on the next tick, since some teleport paths (cross-plugin,
 *    delayed teleport durations, etc.) can bypass cancel.
 *  - Fallback chain: lastSafeLocation -&gt; from-location (if outside) -&gt;
 *    push-to-boundary. The chosen fallback must itself be outside any
 *    safezone, otherwise we walk to the next option.
 *
 * Loop guard: we never teleport if the player's current location is already
 * outside every safezone (cancel already worked), and we never teleport to
 * a location that is itself inside a safezone.
 */
public final class SafezoneMovementListener implements Listener {

    private final HexPvpSmpPlugin plugin;

    public SafezoneMovementListener(HexPvpSmpPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || sameBlockSameWorld(from, to)) {
            return;
        }
        HexPvpConfig config = plugin.config();
        if (config == null || !config.enabled() || !config.safezones().blockEntryWhileCombat()) {
            return;
        }
        Player player = event.getPlayer();
        if (PermissionGate.bypasses(player)) {
            return;
        }
        CombatTagService tagger = plugin.combatTagService();
        ProtectionService protection = plugin.protectionService();

        if (!tagger.isTagged(player)) {
            return;
        }

        List<ProtectedRegion> safezonesAtTo = protection.safezonesAt(to);
        if (safezonesAtTo.isEmpty()) {
            // Tagged player moved freely outside any spawn safezone -- refresh
            // fallback. NO_BUILD zones are not safezones, so entering them is fine.
            tagger.updateLastSafeLocation(player, to);
            return;
        }

        // Cancellation is enough for ordinary movement; record + notify.
        event.setCancelled(true);
        plugin.messageService().sendChat(player, config.messages().safezoneEntryDenied());
        // Client-side visual wall along the edge the player ran into.
        if (plugin.barrierService() != null) {
            plugin.barrierService().showBarrier(player, safezonesAtTo.get(0).cuboid(), from);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        HexPvpConfig config = plugin.config();
        if (config == null || !config.enabled() || !config.safezones().blockEntryWhileCombat()) {
            return;
        }
        Player player = event.getPlayer();
        if (PermissionGate.bypasses(player)) {
            return;
        }
        CombatTagService tagger = plugin.combatTagService();
        ProtectionService protection = plugin.protectionService();

        if (!tagger.isTagged(player)) {
            return;
        }
        if (protection.safezonesAt(to).isEmpty()) {
            tagger.updateLastSafeLocation(player, to);
            return;
        }

        event.setCancelled(true);
        plugin.messageService().sendChat(player, config.messages().safezoneEntryDenied());

        // Defensive fallback: some teleport paths slip past setCancelled.
        // Compute a safe destination NOW; schedule the teleport so we don't
        // interfere with the in-flight event dispatch.
        Location fallback = computeFallback(player, event.getFrom(), to, protection);
        if (fallback == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // Re-check after one tick; loop guard.
            if (!plugin.combatTagService().isTagged(player)) {
                return;
            }
            Location current = player.getLocation();
            if (protection.safezonesAt(current).isEmpty()) {
                return; // cancel already worked
            }
            if (atSameBlock(current, fallback)) {
                return; // already at fallback, avoid loop
            }
            player.teleport(fallback);
        });
    }

    /**
     * Visible for testing. Computes the fallback destination given a player's
     * tagged state, the in-flight event's from/to, and the protection service.
     * Returns null if no safe outside location can be derived.
     */
    public Location computeFallback(Player player, Location from, Location to, ProtectionService protection) {
        CombatState state = plugin.combatTagService().state(player.getUniqueId()).orElse(null);
        if (state != null) {
            Location lastSafe = state.lastSafeLocation();
            if (isSafeOutside(protection, lastSafe)) {
                return lastSafe;
            }
        }
        if (isSafeOutside(protection, from)) {
            return from;
        }
        List<ProtectedRegion> safezonesAtTo = protection.safezonesAt(to);
        if (!safezonesAtTo.isEmpty()) {
            Location pushed = pushToBoundary(to, safezonesAtTo);
            if (isSafeOutside(protection, pushed)) {
                return pushed;
            }
        }
        return null;
    }

    private static boolean isSafeOutside(ProtectionService protection, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        // "Safe" for fallback purposes means outside every spawn safezone.
        return protection.safezonesAt(loc).isEmpty();
    }

    private static boolean atSameBlock(Location a, Location b) {
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

    private boolean sameBlockSameWorld(Location a, Location b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        if (!Objects.equals(a.getWorld().getUID(), b.getWorld().getUID())) {
            return false;
        }
        return a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    /** Push exactly outside the nearest wall of the smallest region we landed in. */
    static Location pushToBoundary(Location to, List<ProtectedRegion> regions) {
        if (to == null || to.getWorld() == null || regions.isEmpty()) {
            return null;
        }
        ProtectedRegion target = regions.get(0);
        var c = target.cuboid();
        double dxMin = Math.abs(to.getX() - c.minX());
        double dxMax = Math.abs(c.maxX() - to.getX());
        double dzMin = Math.abs(to.getZ() - c.minZ());
        double dzMax = Math.abs(c.maxZ() - to.getZ());
        double min = Math.min(Math.min(dxMin, dxMax), Math.min(dzMin, dzMax));

        double x = to.getX();
        double z = to.getZ();
        if (min == dxMin) {
            x = c.minX() - 1.0D;
        } else if (min == dxMax) {
            x = c.maxX() + 1.0D;
        } else if (min == dzMin) {
            z = c.minZ() - 1.0D;
        } else {
            z = c.maxZ() + 1.0D;
        }
        return new Location(to.getWorld(), x, to.getY(), z, to.getYaw(), to.getPitch());
    }
}
