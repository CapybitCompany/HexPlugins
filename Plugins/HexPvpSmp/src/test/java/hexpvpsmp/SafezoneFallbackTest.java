package hexpvpsmp;

import hexpvpsmp.combat.CombatState;
import hexpvpsmp.combat.CombatTagService;
import hexpvpsmp.movement.SafezoneMovementListener;
import hexpvpsmp.protection.ProtectionService;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafezoneFallbackTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;
    private PlayerMock player;
    private SafezoneMovementListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        player = server.addPlayer("Tester");
        listener = new SafezoneMovementListener(plugin);
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Location inside() {
        return new Location(server.getWorld("world"), 0, 64, 0);
    }

    private Location outside() {
        return new Location(server.getWorld("world"), 500, 64, 500);
    }

    private Location otherOutside() {
        return new Location(server.getWorld("world"), 600, 64, 600);
    }

    @Test
    void fallbackPrefersLastSafeLocationWhenPresent() {
        plugin.combatTagService().tag(player);
        // Seed lastSafeLocation via the service directly.
        plugin.combatTagService().updateLastSafeLocation(player, otherOutside());

        ProtectionService protection = plugin.protectionService();
        Location chosen = listener.computeFallback(player, inside(), inside(), protection);
        assertNotNull(chosen);
        assertEquals(otherOutside().getBlockX(), chosen.getBlockX());
        assertEquals(otherOutside().getBlockZ(), chosen.getBlockZ());
    }

    @Test
    void fallbackUsesFromWhenNoLastSafeLocation() {
        plugin.combatTagService().tag(player);
        CombatTagService tagger = plugin.combatTagService();
        CombatState state = tagger.state(player.getUniqueId()).orElseThrow();
        assertEquals(null, state.lastSafeLocation(),
                "precondition: lastSafeLocation starts null");

        ProtectionService protection = plugin.protectionService();
        Location from = outside();
        Location chosen = listener.computeFallback(player, from, inside(), protection);
        assertNotNull(chosen);
        assertEquals(from.getBlockX(), chosen.getBlockX());
        assertEquals(from.getBlockZ(), chosen.getBlockZ());
    }

    @Test
    void fallbackPushesToBoundaryWhenNothingElseSafe() {
        plugin.combatTagService().tag(player);
        ProtectionService protection = plugin.protectionService();
        // Both from and to inside spawn -> must push outside.
        Location from = inside();
        Location to = inside();
        Location chosen = listener.computeFallback(player, from, to, protection);
        assertNotNull(chosen);
        // Must be outside every spawn safezone. With the default no-build ring
        // hugging the spawn box, the pushed point may land in a NO_BUILD zone —
        // that is fine: no-build is not a safezone, so a tagged player may be there.
        assertTrue(protection.safezonesAt(chosen).isEmpty(),
                "push-to-boundary result must be OUTSIDE every safezone");
    }

    @Test
    void taggedMoveOutsideRefreshesLastSafeLocation() {
        plugin.combatTagService().tag(player);
        Location from = outside();
        Location to = otherOutside();
        player.teleport(from);

        org.bukkit.event.player.PlayerMoveEvent event =
                new org.bukkit.event.player.PlayerMoveEvent(player, from, to);
        server.getPluginManager().callEvent(event);

        CombatState state = plugin.combatTagService().state(player.getUniqueId()).orElseThrow();
        assertNotNull(state.lastSafeLocation());
        assertEquals(otherOutside().getBlockX(), state.lastSafeLocation().getBlockX());
    }

    @Test
    void teleportIntoSafezoneIsCancelledAndFallbackQueued() {
        plugin.combatTagService().tag(player);
        plugin.combatTagService().updateLastSafeLocation(player, otherOutside());

        Location from = inside(); // even from is inside, fallback uses lastSafeLocation
        Location to = inside();
        player.teleport(from);

        org.bukkit.event.player.PlayerTeleportEvent event =
                new org.bukkit.event.player.PlayerTeleportEvent(player, from, to,
                        org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "tagged teleport into safezone must be cancelled");

        // Run the scheduled fallback teleport (1-tick later).
        server.getScheduler().performOneTick();

        ProtectionService protection = plugin.protectionService();
        assertTrue(protection.safezonesAt(player.getLocation()).isEmpty(),
                "after fallback the player must be outside any safezone");
    }
}
