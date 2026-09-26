package hexpvpsmp;

import org.bukkit.*;
import org.bukkit.damage.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class ExcludedWorldTest {
    ServerMock server;
    HexPvpSmpPlugin plugin;
    PlayerMock player;
    Location outside, games;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        outside = new Location(server.addSimpleWorld("world"), 500, 64, 500);
        games = new Location(server.addSimpleWorld("Hex_Minigames"), 0, 64, 0);
        plugin = TestPluginLoader.load();
        player = server.addPlayer();
        player.teleport(outside);
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void entryClearsTagAndExitDoesNotRestoreIt() {
        plugin.combatTagService().tag(player);
        assertTrue(plugin.combatTagService().isTagged(player));
        player.teleport(games);
        assertTrue(plugin.combatTagService().snapshot().isEmpty());
        player.teleport(outside);
        assertFalse(plugin.combatTagService().isTagged(player));
    }

    @Test void cancelledEntryDoesNotClearCombat() {
        plugin.combatTagService().tag(player);
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST)
            public void teleport(PlayerTeleportEvent e) { e.setCancelled(true); }
        }, plugin);
        assertFalse(player.teleport(games));
        assertTrue(plugin.combatTagService().isTagged(player));
    }

    @Test void hitsAndCommandsAreLeftToMinigames() {
        player.teleport(games);
        PlayerMock attacker = server.addPlayer();
        attacker.teleport(games);
        var hit = new EntityDamageByEntityEvent(attacker, player,
                EntityDamageByEntityEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.PLAYER_ATTACK).build(), 1);
        server.getPluginManager().callEvent(hit);
        assertFalse(hit.isCancelled());
        assertTrue(plugin.combatTagService().snapshot().isEmpty());
        plugin.combatTagService().tag(player);
        var command = new PlayerCommandPreprocessEvent(player, "/lobby");
        server.getPluginManager().callEvent(command);
        assertFalse(command.isCancelled());
    }

    @Test void quitPreservesRestoredInventoryEvenAfterMinigamesTeleportsToSmp() {
        plugin.combatTagService().tag(player);
        player.teleport(games);
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void quit(PlayerQuitEvent e) {
                e.getPlayer().teleport(outside);
                e.getPlayer().getInventory().setItem(0, new ItemStack(Material.DIAMOND, 12));
                e.getPlayer().setTotalExperience(123);
            }
        }, plugin);
        server.getPluginManager().callEvent(new PlayerQuitEvent(player, (String) null));
        assertEquals(12, player.getInventory().getItem(0).getAmount());
        assertEquals(123, player.getTotalExperience());
        assertEquals(0, player.getStatistic(Statistic.DEATHS));
    }

    @Test void excludedConfiguredRegionsAreIgnoredAndAliasesWork() {
        assertTrue(plugin.config().excludesWorld("HEXMINIGAMES"));
        plugin.getConfig().set("excluded-worlds", java.util.List.of("world", "Hex_Minigames"));
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());
        assertTrue(plugin.protectionService().allRegions().isEmpty());
        assertFalse(plugin.protectionService().isPvpProtected("world", 0, 64, 0));
        assertFalse(plugin.publicChestRegistry().isPublicChest("world", 0, 65, 0));
    }

    @Test void excludedPlayerReceivesNoCombatOrSafezoneUi() {
        var recipient = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        org.mockito.Mockito.when(recipient.getLocation()).thenReturn(games);
        plugin.messageService().sendActionBar(recipient, "combat");
        plugin.messageService().sendActionBarUnthrottled(recipient, "combat");
        plugin.messageService().showTitle(recipient, "safezone", "safezone");
        org.mockito.Mockito.verify(recipient, org.mockito.Mockito.never()).sendActionBar(
                org.mockito.ArgumentMatchers.any(net.kyori.adventure.text.Component.class));
        org.mockito.Mockito.verify(recipient, org.mockito.Mockito.never()).showTitle(
                org.mockito.ArgumentMatchers.any(net.kyori.adventure.title.Title.class));
    }
}
