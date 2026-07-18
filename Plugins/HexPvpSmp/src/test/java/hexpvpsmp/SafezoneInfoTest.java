package hexpvpsmp;

import hexpvpsmp.movement.SafezoneInfoListener;
import hexpvpsmp.movement.SafezoneInfoListener.Transition;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafezoneInfoTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        player = server.addPlayer("Walker");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void classifyTransitions() {
        assertEquals(Transition.ENTER, SafezoneInfoListener.classify(false, true));
        assertEquals(Transition.EXIT, SafezoneInfoListener.classify(true, false));
        assertEquals(Transition.NONE, SafezoneInfoListener.classify(true, true));
        assertEquals(Transition.NONE, SafezoneInfoListener.classify(false, false));
    }

    private SafezoneInfoListener listener() {
        return new SafezoneInfoListener(plugin);
    }

    // decide(id, fromInside, toInside, nowTick, cooldown). The Adventure title
    // transport is not captured by MockBukkit, so the enter/exit *decision* is
    // asserted here; the move-event wiring is smoke-tested above.

    @Test
    void firstMoveOutsideToInsideShowsEntry() {
        SafezoneInfoListener l = listener();
        assertEquals(Transition.ENTER, l.decide(player.getUniqueId(), false, true, 0L, 40),
                "first tracked move outside->inside must resolve to ENTER");
    }

    @Test
    void firstMoveInsideToOutsideShowsExit() {
        SafezoneInfoListener l = listener();
        assertEquals(Transition.EXIT, l.decide(player.getUniqueId(), true, false, 0L, 40),
                "first tracked move inside->outside must resolve to EXIT");
    }

    @Test
    void movingWithinSafezoneShowsNothing() {
        SafezoneInfoListener l = listener();
        assertEquals(Transition.NONE, l.decide(player.getUniqueId(), true, true, 0L, 40));
    }

    @Test
    void movingOutsideShowsNothing() {
        SafezoneInfoListener l = listener();
        assertEquals(Transition.NONE, l.decide(player.getUniqueId(), false, false, 0L, 40));
    }

    @Test
    void cooldownSuppressesRepeatTransitions() {
        SafezoneInfoListener l = listener();
        var id = player.getUniqueId();
        assertEquals(Transition.ENTER, l.decide(id, false, true, 100L, 40), "first transition shown");
        // Leave 10 ticks later -> within the 40-tick cooldown -> suppressed
        // (state still advances to 'outside').
        assertEquals(Transition.NONE, l.decide(id, true, false, 110L, 40),
                "transition within cooldown window is suppressed");
        // Re-enter well after the cooldown -> shown again.
        assertEquals(Transition.ENTER, l.decide(id, false, true, 200L, 40),
                "transition after cooldown is shown");
    }

    @Test
    void moveEventPathDoesNotThrow() {
        Location outside = new Location(server.getWorld("world"), 500, 64, 500);
        Location inside = new Location(server.getWorld("world"), 0, 64, 0);
        player.teleport(outside);
        server.getPluginManager().callEvent(new PlayerMoveEvent(player, outside, inside));
        server.getPluginManager().callEvent(new PlayerMoveEvent(player, inside, outside));
    }
}
