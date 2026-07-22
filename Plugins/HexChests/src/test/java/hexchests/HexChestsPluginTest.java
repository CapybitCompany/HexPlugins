package hexchests;

import hexchests.gui.HexChestsGuiHolder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexChestsPluginTest {

    private ServerMock server;
    private HexChestsPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexChestsPlugin.class);
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void shouldLoadServicesAndConfiguredChests() {
        assertNotNull(plugin.config());
        assertNotNull(plugin.keyService());
        assertNotNull(plugin.chestService());

        assertTrue(plugin.chestService().chestAt(location(2772, 75, 939)).isPresent());
        assertTrue(plugin.chestService().chestAt(location(2775, 75, 940)).isPresent());
        assertTrue(plugin.chestService().chestAt(location(2776, 75, 943)).isPresent());
        assertFalse(plugin.chestService().chestAt(location(2771, 75, 939)).isPresent());
    }

    @Test
    void shouldRunReloadAndGiveKeyCommands() {
        Player op = server.addPlayer("OpUser");
        op.setOp(true);

        assertTrue(op.performCommand("hexchests reload"));
        assertTrue(op.performCommand("hexchests afkkey"));

        ItemStack key = op.getInventory().getItem(0);
        assertNotNull(key);
        assertEquals(Material.TRIPWIRE_HOOK, key.getType());
        assertEquals("afk", plugin.keyService().keyId(key).orElseThrow());
    }

    @Test
    void shouldRejectAnvilNamedFakeKeyAndOpenPreview() {
        Player player = server.addPlayer("FakeKeyUser");
        ItemStack fakeKey = new ItemStack(Material.DIRT);
        ItemMeta meta = fakeKey.getItemMeta();
        meta.displayName(Text.component("&eKlucz AFK"));
        fakeKey.setItemMeta(meta);
        player.getInventory().setItemInMainHand(fakeKey);

        var chest = plugin.config().chests().get("afk");
        plugin.chestService().handleRightClick(player, chest, player.getInventory().getItemInMainHand());

        assertFalse(plugin.chestService().hasActiveOpening(player.getUniqueId()));
        assertInstanceOf(HexChestsGuiHolder.class, player.getOpenInventory().getTopInventory().getHolder());
        HexChestsGuiHolder holder = (HexChestsGuiHolder) player.getOpenInventory().getTopInventory().getHolder();
        assertEquals(HexChestsGuiHolder.Mode.PREVIEW, holder.mode());
    }

    @Test
    void shouldShowPreviewOnLeftClickAndRightClickWithoutKey() {
        Player player = server.addPlayer("PreviewUser");
        var chest = plugin.config().chests().get("epic");

        plugin.chestService().handleLeftClick(player, chest);
        HexChestsGuiHolder leftHolder = (HexChestsGuiHolder) player.getOpenInventory().getTopInventory().getHolder();
        assertEquals(HexChestsGuiHolder.Mode.PREVIEW, leftHolder.mode());

        player.closeInventory();
        plugin.chestService().handleRightClick(player, chest, new ItemStack(Material.STONE));
        HexChestsGuiHolder rightHolder = (HexChestsGuiHolder) player.getOpenInventory().getTopInventory().getHolder();
        assertEquals(HexChestsGuiHolder.Mode.PREVIEW, rightHolder.mode());
    }

    @Test
    void shouldStartOpeningOnlyWithMatchingKeyAndConsumeIt() {
        Player player = server.addPlayer("OpeningUser");
        ItemStack wrongKey = plugin.keyService().createKey("premium", 1);
        player.getInventory().setItemInMainHand(wrongKey);

        var afk = plugin.config().chests().get("afk");
        plugin.chestService().handleRightClick(player, afk, player.getInventory().getItemInMainHand());
        assertFalse(plugin.chestService().hasActiveOpening(player.getUniqueId()));
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());

        ItemStack correctKey = plugin.keyService().createKey("afk", 2);
        player.getInventory().setItemInMainHand(correctKey);
        plugin.chestService().handleRightClick(player, afk, player.getInventory().getItemInMainHand());

        assertTrue(plugin.chestService().hasActiveOpening(player.getUniqueId()));
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        HexChestsGuiHolder holder = (HexChestsGuiHolder) player.getOpenInventory().getTopInventory().getHolder();
        assertEquals(HexChestsGuiHolder.Mode.OPENING, holder.mode());
    }

    @Test
    void shouldExecuteRewardCommandsWhenOpeningFinishes() {
        List<String> executed = new ArrayList<>();
        server.getCommandMap().register("hexcheststest", new Command("hctest") {
            @Override
            public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
                executed.add(String.join(" ", args));
                return true;
            }
        });
        plugin.getConfig().set("chests.afk.rewards", null);
        plugin.getConfig().set("chests.afk.rewards.only.material", "DIAMOND");
        plugin.getConfig().set("chests.afk.rewards.only.display-name", "Testowa nagroda");
        plugin.getConfig().set("chests.afk.rewards.only.amount", 4);
        plugin.getConfig().set("chests.afk.rewards.only.chance", 100.0);
        plugin.getConfig().set("chests.afk.rewards.only.commands", List.of("hctest {player} {chest} {reward} {amount}"));
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        Player player = server.addPlayer("RewardUser");
        player.getInventory().setItemInMainHand(plugin.keyService().createKey("afk", 1));
        plugin.chestService().handleRightClick(player, plugin.config().chests().get("afk"),
                player.getInventory().getItemInMainHand());

        assertTrue(plugin.chestService().hasActiveOpening(player.getUniqueId()));
        plugin.chestService().finishActiveOpening(player);

        assertFalse(plugin.chestService().hasActiveOpening(player.getUniqueId()));
        assertEquals(List.of("RewardUser afk only 4"), executed);
    }

    private Location location(int x, int y, int z) {
        return new Location(server.getWorld("world"), x, y, z);
    }
}
