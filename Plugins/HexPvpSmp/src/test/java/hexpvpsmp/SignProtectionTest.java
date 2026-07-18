package hexpvpsmp;

import hexpvpsmp.combat.PermissionGate;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.block.SignChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignProtectionTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        player = server.addPlayer("Writer");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Block signAt(int x, int y, int z) {
        Block block = server.getWorld("world").getBlockAt(x, y, z);
        block.setType(Material.OAK_SIGN);
        return block;
    }

    private boolean fireSignChange(Block block) {
        SignChangeEvent event = new SignChangeEvent(
                block, player, new String[]{"a", "b", "c", "d"});
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    // ---- SignChangeEvent -------------------------------------------------

    @Test
    void signChangeBlockedInSpawnAndNoBuild() {
        assertTrue(fireSignChange(signAt(10, 64, 10)), "sign edit in spawn blocked");
        assertTrue(fireSignChange(signAt(10, 64, 150)), "sign edit in no-build blocked");
    }

    @Test
    void signChangeAllowedInWilderness() {
        assertFalse(fireSignChange(signAt(500, 64, 500)), "sign edit in wilderness allowed");
    }

    @Test
    void signChangeAllowedForBypassByDefault() {
        player.addAttachment(plugin, PermissionGate.BYPASS_PERMISSION, true);
        assertFalse(fireSignChange(signAt(10, 64, 10)), "bypass player may edit signs");
    }

    @Test
    void signChangeBlockedForBypassWhenInteractBypassOff() {
        player.addAttachment(plugin, PermissionGate.BYPASS_PERMISSION, true);
        plugin.getConfig().set("protection.bypass.interact", false);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
        assertTrue(fireSignChange(signAt(10, 64, 10)),
                "bypass.interact=false enforces sign protection even for bypass");
    }

    // ---- PlayerOpenSignEvent (Paper) ------------------------------------

    private boolean fireSignOpen(Block block) {
        Sign sign;
        try {
            sign = (Sign) block.getState();
        } catch (ClassCastException ex) {
            Assumptions.abort("MockBukkit does not provide a Sign block state");
            return false; // unreachable
        }
        Assumptions.assumeTrue(sign != null, "sign state unavailable");
        PlayerOpenSignEvent event = new PlayerOpenSignEvent(
                player, sign, Side.FRONT, PlayerOpenSignEvent.Cause.INTERACT);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void signOpenBlockedInSpawn() {
        assertTrue(fireSignOpen(signAt(10, 64, 10)), "opening a sign editor in spawn blocked");
    }

    @Test
    void signOpenAllowedInWilderness() {
        assertFalse(fireSignOpen(signAt(500, 64, 500)), "opening a sign in wilderness allowed");
    }
}
