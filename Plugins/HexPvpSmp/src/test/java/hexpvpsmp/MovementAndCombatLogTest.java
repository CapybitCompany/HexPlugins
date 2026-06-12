package hexpvpsmp;

import hexpvpsmp.combat.CombatState;
import hexpvpsmp.combat.CombatTagService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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

class MovementAndCombatLogTest {

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

    private Location inside() {
        return new Location(server.getWorld("world"), 0, 64, 0);
    }

    @Test
    void taggedMoveIntoSafezoneIsCancelled() {
        Location from = outside();
        Location to = inside();
        player.teleport(from);
        plugin.combatTagService().tag(player);

        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "tagged move into spawn must be cancelled");
    }

    @Test
    void untaggedMoveIntoSafezoneIsAllowed() {
        Location from = outside();
        Location to = inside();
        player.teleport(from);

        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
        server.getPluginManager().callEvent(event);

        assertFalse(event.isCancelled(), "untagged player can freely enter spawn");
    }

    @Test
    void taggedTeleportIntoSafezoneIsCancelled() {
        Location from = outside();
        Location to = inside();
        player.teleport(from);
        plugin.combatTagService().tag(player);

        PlayerTeleportEvent event = new PlayerTeleportEvent(player, from, to,
                PlayerTeleportEvent.TeleportCause.COMMAND);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled(), "tagged teleport into spawn must be cancelled");
    }

    @Test
    void taggedMoveOutsideUpdatesLastSafeLocation() {
        Location from = outside();
        Location to = new Location(server.getWorld("world"), 600, 64, 600);
        player.teleport(from);
        plugin.combatTagService().tag(player);

        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
        server.getPluginManager().callEvent(event);

        CombatState state = plugin.combatTagService().state(player.getUniqueId()).orElseThrow();
        assertNotNull(state.lastSafeLocation(), "lastSafeLocation should be set after a safe move");
        assertEquals(600, state.lastSafeLocation().getBlockX());
    }

    @Test
    void rotationOnlyMoveIsIgnored() {
        Location from = outside();
        Location to = from.clone();
        to.setYaw(90.0f);
        player.teleport(from);
        plugin.combatTagService().tag(player);

        PlayerMoveEvent event = new PlayerMoveEvent(player, from, to);
        server.getPluginManager().callEvent(event);

        // Not cancelled and lastSafeLocation not refreshed.
        assertFalse(event.isCancelled());
    }

    @Test
    void combatLogDropsInventoryClearsAndUntags() {
        Location at = outside();
        player.teleport(at);
        plugin.combatTagService().tag(player);

        player.getInventory().setContents(new ItemStack[]{
                new ItemStack(Material.DIAMOND, 5),
                new ItemStack(Material.IRON_INGOT, 32)
        });

        PlayerQuitEvent event = new PlayerQuitEvent(player, (String) null);
        server.getPluginManager().callEvent(event);

        // Tag removed
        assertFalse(plugin.combatTagService().isTagged(player.getUniqueId()));
        // Inventory cleared
        for (ItemStack item : player.getInventory().getContents()) {
            assertTrue(item == null || item.getType() == Material.AIR,
                    "inventory must be cleared after combat-log");
        }
    }

    @Test
    void untaggedQuitIsNoOp() {
        Location at = outside();
        player.teleport(at);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));

        PlayerQuitEvent event = new PlayerQuitEvent(player, (String) null);
        server.getPluginManager().callEvent(event);

        ItemStack first = player.getInventory().getItem(0);
        assertNotNull(first);
        assertEquals(Material.DIAMOND, first.getType());
        assertEquals(5, first.getAmount());
    }
}
