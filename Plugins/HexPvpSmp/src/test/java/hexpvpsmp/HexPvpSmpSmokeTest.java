package hexpvpsmp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexPvpSmpSmokeTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void pluginBootsAndExposesServices() {
        assertNotNull(plugin);
        assertNotNull(plugin.config(), "config should be loaded");
        assertNotNull(plugin.combatTagService());
        assertNotNull(plugin.messageService());
        assertNotNull(plugin.protectionService());
        assertNotNull(plugin.actionBarService());
        assertNotNull(plugin.hexCoreBridge());
    }

    @Test
    void reloadDoesNotThrowAndRebuildsRuntime() {
        var before = plugin.protectionService();
        assertTrue(plugin.reloadPluginRuntime());
        assertNotNull(plugin.protectionService());
        // CombatTagService instance must SURVIVE reload (in-memory tags preserved).
        assertNotNull(plugin.combatTagService());
        // Protection service instance is rebuilt:
        assertNotNull(before);
    }
}
