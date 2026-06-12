package hexpvpsmp;

import hexpvpsmp.config.CombatConfig;
import hexpvpsmp.config.HexPvpConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
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

class CombatLogPolicyTest {

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

    private Location outside() {
        return new Location(server.getWorld("world"), 500, 64, 500);
    }

    private void setCombatLog(boolean enabled, boolean dropInv, boolean dropExp) {
        plugin.getConfig().set("combat.combat-log.enabled", enabled);
        plugin.getConfig().set("combat.combat-log.drop-inventory", dropInv);
        plugin.getConfig().set("combat.combat-log.drop-exp", dropExp);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
    }

    private void giveInventoryAndExp() {
        player.teleport(outside());
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        player.giveExpLevels(10);
    }

    @Test
    void disabledCombatLogClearsTagButPreservesItemsAndExp() {
        setCombatLog(false, true, true);
        plugin.combatTagService().tag(player);
        giveInventoryAndExp();
        int beforeLevel = player.getLevel();

        server.getPluginManager().callEvent(new PlayerQuitEvent(player, (String) null));

        // Tag cleared regardless of policy
        assertFalse(plugin.combatTagService().isTagged(player.getUniqueId()));
        // Inventory preserved
        ItemStack first = player.getInventory().getItem(0);
        assertNotNull(first);
        assertEquals(Material.DIAMOND, first.getType());
        assertEquals(5, first.getAmount());
        // Levels preserved
        assertEquals(beforeLevel, player.getLevel());
    }

    @Test
    void enabledCombatLogWithDropInventoryFalsePreservesItems() {
        setCombatLog(true, false, true);
        plugin.combatTagService().tag(player);
        giveInventoryAndExp();

        server.getPluginManager().callEvent(new PlayerQuitEvent(player, (String) null));

        assertFalse(plugin.combatTagService().isTagged(player.getUniqueId()));
        // Inventory preserved because drop-inventory=false
        ItemStack first = player.getInventory().getItem(0);
        assertNotNull(first);
        assertEquals(Material.DIAMOND, first.getType());
        // XP drained because drop-exp=true
        assertEquals(0, player.getLevel());
    }

    @Test
    void enabledCombatLogWithDropExpFalsePreservesExp() {
        setCombatLog(true, true, false);
        plugin.combatTagService().tag(player);
        giveInventoryAndExp();
        int beforeLevel = player.getLevel();

        server.getPluginManager().callEvent(new PlayerQuitEvent(player, (String) null));

        assertFalse(plugin.combatTagService().isTagged(player.getUniqueId()));
        // Inventory dropped because drop-inventory=true
        ItemStack first = player.getInventory().getItem(0);
        assertTrue(first == null || first.getType() == Material.AIR);
        // XP preserved because drop-exp=false
        assertEquals(beforeLevel, player.getLevel());
    }

    @Test
    void enabledCombatLogClearsEverything() {
        setCombatLog(true, true, true);
        plugin.combatTagService().tag(player);
        giveInventoryAndExp();

        server.getPluginManager().callEvent(new PlayerQuitEvent(player, (String) null));

        assertFalse(plugin.combatTagService().isTagged(player.getUniqueId()));
        for (ItemStack item : player.getInventory().getContents()) {
            assertTrue(item == null || item.getType() == Material.AIR);
        }
        assertEquals(0, player.getLevel());
    }

    @Test
    void legacyKillPlayerKeyIsHonoredForBackwardsCompat() {
        // Old key under the same combat-log section, "enabled" missing.
        plugin.getConfig().set("combat.combat-log.enabled", null);
        plugin.getConfig().set("combat.combat-log.kill-player", false);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        HexPvpConfig config = plugin.config();
        CombatConfig.CombatLog cl = config.combat().combatLog();
        assertFalse(cl.enabled(), "kill-player=false should map to enabled=false");
    }
}
