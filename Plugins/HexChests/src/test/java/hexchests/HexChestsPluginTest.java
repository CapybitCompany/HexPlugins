package hexchests;

import hexchests.gui.HexChestsGuiHolder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexChestsPluginTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

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

    @Test
    void shouldAwardVanillaItemsByDefaultWhenPreviewMetaIsConfigured() {
        plugin.getConfig().set("chests.afk.rewards", null);
        plugin.getConfig().set("chests.afk.rewards.only.material", "EMERALD");
        plugin.getConfig().set("chests.afk.rewards.only.display-name", "&aSzmaragdy");
        plugin.getConfig().set("chests.afk.rewards.only.lore", List.of("&7Widoczne tylko w podgladzie"));
        plugin.getConfig().set("chests.afk.rewards.only.amount", 24);
        plugin.getConfig().set("chests.afk.rewards.only.chance", 100.0);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        ItemStack reward = finishAfkOpening("VanillaRewardUser");

        assertEquals(Material.EMERALD, reward.getType());
        assertEquals(24, reward.getAmount());
        assertFalse(reward.hasItemMeta() && reward.getItemMeta().hasDisplayName());
        assertFalse(reward.hasItemMeta() && reward.getItemMeta().hasLore());
    }

    @Test
    void shouldApplyDropNameAndLoreOnlyWhenConfiguredForDrop() {
        plugin.getConfig().set("chests.afk.rewards", null);
        plugin.getConfig().set("chests.afk.rewards.only.material", "DIAMOND");
        plugin.getConfig().set("chests.afk.rewards.only.display-name", "&bDiament w podgladzie");
        plugin.getConfig().set("chests.afk.rewards.only.drop-display-name", "&bDiament nagrody");
        plugin.getConfig().set("chests.afk.rewards.only.drop-lore", List.of("&7Lore nagrody"));
        plugin.getConfig().set("chests.afk.rewards.only.amount", 1);
        plugin.getConfig().set("chests.afk.rewards.only.chance", 100.0);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        ItemStack reward = finishAfkOpening("CustomRewardUser");
        ItemMeta meta = reward.getItemMeta();

        assertEquals(Material.DIAMOND, reward.getType());
        assertNotNull(meta);
        assertTrue(meta.hasDisplayName());
        assertTrue(meta.hasLore());
        assertEquals("Diament nagrody", PLAIN.serialize(meta.displayName()));
        assertEquals(List.of("Lore nagrody"), meta.lore().stream().map(PLAIN::serialize).toList());
    }

    @Test
    void shouldKeepChestChanceOnFinalOpeningItem() {
        plugin.getConfig().set("chests.afk.rewards", null);
        plugin.getConfig().set("chests.afk.rewards.common.material", "EMERALD");
        plugin.getConfig().set("chests.afk.rewards.common.display-name", "&aCommon");
        plugin.getConfig().set("chests.afk.rewards.common.amount", 1);
        plugin.getConfig().set("chests.afk.rewards.common.chance", 25.0);
        plugin.getConfig().set("chests.afk.rewards.rare.material", "DIAMOND");
        plugin.getConfig().set("chests.afk.rewards.rare.display-name", "&bRare");
        plugin.getConfig().set("chests.afk.rewards.rare.amount", 1);
        plugin.getConfig().set("chests.afk.rewards.rare.chance", 75.0);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        Player player = startAfkOpening("ChanceUser");
        var session = plugin.chestService().opening(player.getUniqueId()).orElseThrow();
        double totalChance = plugin.config().chests().get("afk").rewards().stream()
                .mapToDouble(reward -> reward.chance())
                .sum();
        String expectedChance = String.format(Locale.US, "%.1f", (session.reward().chance() / totalChance) * 100.0D);

        plugin.chestService().finishActiveOpening(player);

        ItemStack result = player.getOpenInventory().getTopInventory()
                .getItem(plugin.config().gui().opening().resultSlot());
        assertNotNull(result);
        assertNotNull(result.getItemMeta());
        List<String> lore = result.getItemMeta().lore().stream().map(PLAIN::serialize).toList();
        assertTrue(lore.stream().anyMatch(line -> line.contains(expectedChance + "%")));
        assertFalse(lore.stream().anyMatch(line -> line.contains("100.0%")));
    }

    private Player startAfkOpening(String playerName) {
        Player player = server.addPlayer(playerName);
        player.getInventory().setItemInMainHand(plugin.keyService().createKey("afk", 1));
        plugin.chestService().handleRightClick(player, plugin.config().chests().get("afk"),
                player.getInventory().getItemInMainHand());
        assertTrue(plugin.chestService().hasActiveOpening(player.getUniqueId()));
        return player;
    }

    private ItemStack finishAfkOpening(String playerName) {
        Player player = startAfkOpening(playerName);
        plugin.chestService().finishActiveOpening(player);
        ItemStack reward = player.getInventory().getItem(0);
        assertNotNull(reward);
        return reward;
    }

    private Location location(int x, int y, int z) {
        return new Location(server.getWorld("world"), x, y, z);
    }
}
