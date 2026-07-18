package hexpvpsmp;

import hexpvpsmp.combat.PermissionGate;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-chest indestructibility vs bypass, and ignite/candle bypass consistency. */
class BypassAndIgniteTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
        player = server.addPlayer("Admin");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Block blockAt(int x, int y, int z) {
        return server.getWorld("world").getBlockAt(x, y, z);
    }

    private boolean fireBreak(Block block) {
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean firePlayerIgnite(Block block) {
        BlockIgniteEvent event = new BlockIgniteEvent(
                block, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, player);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    // ---- Public chest indestructibility ---------------------------------

    @Test
    void opCanBreakNormalSpawnBlockWhenBypassBuild() {
        player.setOp(true); // bypass; protection.bypass.build defaults true
        assertFalse(fireBreak(blockAt(10, 64, 10)),
                "OP/bypass may break a normal spawn block when bypass.build=true");
    }

    @Test
    void opCannotBreakPublicChest() {
        player.setOp(true);
        Block chest = blockAt(0, 65, 0); // configured public chest
        chest.setType(Material.CHEST);
        assertTrue(fireBreak(chest),
                "public chest must be indestructible even for OP/bypass");
    }

    @Test
    void bypassPermissionCannotBreakPublicChest() {
        player.addAttachment(plugin, PermissionGate.BYPASS_PERMISSION, true);
        Block chest = blockAt(0, 65, 0);
        chest.setType(Material.CHEST);
        assertTrue(fireBreak(chest),
                "public chest must be indestructible even with hexpvpsmp.bypass");
    }

    @Test
    void normalPlayerCannotBreakPublicChest() {
        Block chest = blockAt(0, 65, 0);
        chest.setType(Material.CHEST);
        assertTrue(fireBreak(chest), "normal player cannot break a public chest");
    }

    // ---- Ignite / candle bypass -----------------------------------------

    @Test
    void normalPlayerIgniteBlockedInSpawn() {
        assertTrue(firePlayerIgnite(blockAt(10, 64, 10)),
                "a normal player must not ignite/light in spawn");
    }

    @Test
    void opIgniteAllowedWhenInteractBypassOn() {
        player.setOp(true); // protection.bypass.interact defaults true
        assertFalse(firePlayerIgnite(blockAt(10, 64, 10)),
                "OP/bypass may ignite when bypass.interact=true");
    }

    @Test
    void opIgniteBlockedWhenInteractBypassOff() {
        player.setOp(true);
        plugin.getConfig().set("protection.bypass.interact", false);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
        assertTrue(firePlayerIgnite(blockAt(10, 64, 10)),
                "bypass.interact=false enforces ignite protection even for OP");
    }

    @Test
    void environmentalIgniteAlwaysBlockedInSpawn() {
        BlockIgniteEvent event = new BlockIgniteEvent(
                blockAt(10, 64, 10), BlockIgniteEvent.IgniteCause.SPREAD, blockAt(500, 64, 500));
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled(),
                "environmental (no-player) ignite in spawn is always blocked");
    }
}
