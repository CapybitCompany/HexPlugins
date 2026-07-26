package hexpvpsmp;

import hexpvpsmp.protection.NativeSpawnProtectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle of the native server spawn-protection radius that HexPvpSmp manages.
 * Covers the pure manager and the plugin integration (enable / disable / reload).
 */
class NativeSpawnProtectionTest {

    private static final Logger LOGGER = Logger.getLogger("NativeSpawnProtectionTest");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    // ---- Manager unit lifecycle -----------------------------------------

    @Test
    void applyCapturesOriginalAndForcesZero() {
        server.setSpawnRadius(16);
        NativeSpawnProtectionManager manager = new NativeSpawnProtectionManager(server, LOGGER);

        manager.apply(true);

        assertEquals(0, server.getSpawnRadius(), "native radius must be forced to 0 while managed");
        assertEquals(16, manager.originalRadius(), "original radius must be captured");
        assertTrue(manager.isManaging());
    }

    @Test
    void restoreReturnsOriginalRadius() {
        server.setSpawnRadius(24);
        NativeSpawnProtectionManager manager = new NativeSpawnProtectionManager(server, LOGGER);
        manager.apply(true);

        manager.restore();

        assertEquals(24, server.getSpawnRadius(), "original radius must be restored on restore()");
        assertFalse(manager.isManaging());
    }

    @Test
    void repeatedApplyDoesNotLoseOriginalRadius() {
        server.setSpawnRadius(16);
        NativeSpawnProtectionManager manager = new NativeSpawnProtectionManager(server, LOGGER);

        manager.apply(true); // 16 -> 0, original = 16
        manager.apply(true); // reload: still 0, original stays 16

        assertEquals(0, server.getSpawnRadius());
        assertEquals(16, manager.originalRadius(), "reload must not treat the forced 0 as the original");

        manager.restore();
        assertEquals(16, server.getSpawnRadius(), "restore after reload must return the real original");
    }

    @Test
    void applyWithDisableFalseLeavesRadiusUntouched() {
        server.setSpawnRadius(16);
        NativeSpawnProtectionManager manager = new NativeSpawnProtectionManager(server, LOGGER);

        manager.apply(false);

        assertEquals(16, server.getSpawnRadius(), "disable-native=false must leave the native radius as-is");
        assertFalse(manager.isManaging());
        assertNull(manager.originalRadius());
    }

    @Test
    void adminTurningManagementOffViaReloadRestoresRadius() {
        server.setSpawnRadius(16);
        NativeSpawnProtectionManager manager = new NativeSpawnProtectionManager(server, LOGGER);
        manager.apply(true); // 16 -> 0

        manager.apply(false); // admin set disable-native=false, then reloaded

        assertEquals(16, server.getSpawnRadius(), "switching management off must hand the radius back");
        assertFalse(manager.isManaging());
    }

    @Test
    void restoreIsNoOpWhenNeverApplied() {
        server.setSpawnRadius(16);
        NativeSpawnProtectionManager manager = new NativeSpawnProtectionManager(server, LOGGER);

        manager.restore();

        assertEquals(16, server.getSpawnRadius());
        assertFalse(manager.isManaging());
    }

    @Test
    void externalRadiusChangeIsNotOverwrittenOnRestore() {
        server.setSpawnRadius(16);
        NativeSpawnProtectionManager manager = new NativeSpawnProtectionManager(server, LOGGER);
        manager.apply(true);            // 16 -> 0
        server.setSpawnRadius(8);       // something else takes control

        manager.restore();              // we no longer control it

        assertEquals(8, server.getSpawnRadius(), "restore must not clobber an externally changed radius");
        assertFalse(manager.isManaging());
    }

    // ---- Plugin integration ---------------------------------------------

    @Test
    void pluginEnableForcesNativeRadiusToZeroByDefault() {
        server.setSpawnRadius(16);
        HexPvpSmpPlugin plugin = MockBukkit.load(HexPvpSmpPlugin.class);

        assertEquals(0, server.getSpawnRadius(),
                "enabling HexPvpSmp must disable native spawn protection by default");
        assertTrue(plugin.nativeSpawnProtection().isManaging());
    }

    @Test
    void pluginDisableRestoresNativeRadius() {
        server.setSpawnRadius(16);
        HexPvpSmpPlugin plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        assertEquals(0, server.getSpawnRadius());

        server.getPluginManager().disablePlugin(plugin);

        assertEquals(16, server.getSpawnRadius(), "disabling HexPvpSmp must restore the native radius");
    }

    @Test
    void configuredFalseKeepsNativeRadius() {
        server.setSpawnRadius(16);
        HexPvpSmpPlugin plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        assertEquals(0, server.getSpawnRadius(), "default forces 0");

        plugin.getConfig().set("protection.disable-native-spawn-protection", false);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        assertEquals(16, server.getSpawnRadius(),
                "disable-native-spawn-protection=false must hand the native radius back on reload");
        assertFalse(plugin.nativeSpawnProtection().isManaging());
    }
}
