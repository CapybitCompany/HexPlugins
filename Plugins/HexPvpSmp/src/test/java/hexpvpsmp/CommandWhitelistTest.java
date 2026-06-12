package hexpvpsmp;

import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandWhitelistTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        player = server.addPlayer("Tester");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private boolean fireAndIsCancelled(String message) {
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, message);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void taggedPlayerBlockedFromNonWhitelistedCommand() {
        plugin.combatTagService().tag(player);
        assertTrue(fireAndIsCancelled("/spawn"),
                "non-whitelisted command must be cancelled while tagged");
    }

    @Test
    void taggedPlayerAllowedWhitelistedCommand() {
        plugin.combatTagService().tag(player);
        assertFalse(fireAndIsCancelled("/msg Friend hi"),
                "whitelisted /msg must pass while tagged");
        assertFalse(fireAndIsCancelled("/help"),
                "whitelisted /help must pass while tagged");
    }

    @Test
    void untaggedPlayerIsNeverBlocked() {
        assertFalse(fireAndIsCancelled("/anything"));
    }

    @Test
    void opBypassesWhitelistEvenWhileTagged() {
        player.setOp(true);
        plugin.combatTagService().tag(player);
        assertFalse(fireAndIsCancelled("/op-only-command"));
    }

    @Test
    void namespaceAndAliasPrefixesAreNormalized() {
        plugin.combatTagService().tag(player);
        // minecraft:tell -> tell (whitelisted)
        assertFalse(fireAndIsCancelled("/minecraft:tell Friend hi"));
        // unknown namespace + non-whitelisted label
        assertTrue(fireAndIsCancelled("/anything:spawn"));
    }
}
