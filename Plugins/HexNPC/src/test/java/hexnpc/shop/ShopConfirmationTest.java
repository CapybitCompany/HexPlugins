package hexnpc.shop;

import hexnpc.HexNpcPlugin;
import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.gui.ConfirmAction;
import hexnpc.shop.gui.ShopGuiBuilder;
import hexnpc.shop.gui.ShopGuiHolder;
import hexnpc.shop.limit.DailyBuyLimitService;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import hexnpc.shop.sign.SignInputService;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Potwierdzenie transakcji > threshold: 64 wykonuje wprost, 65 otwiera
 * potwierdzenie bez żadnej zmiany, potwierdzenie wykonuje raz i ponownie
 * waliduje, anulowanie nic nie robi, a „Sprzedaj wszystko" jest przeliczane.
 */
class ShopConfirmationTest {

    private ServerMock server;
    private HexNpcPlugin plugin;
    private PlayerMock player;
    private RecordingEconomyBridge economy;
    private DailyBuyLimitService buyLimits;
    private ShopService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        ShopGuiBuilder.setInventoryFactory((h, s, t) ->
                Bukkit.createInventory(h, s, LegacyComponentSerializer.legacySection().serialize(t)));
        plugin = MockBukkit.load(HexNpcPlugin.class);
        player = server.addPlayer("Trader");
        economy = new RecordingEconomyBridge();
        buyLimits = new DailyBuyLimitService(null, Logger.getLogger("test"));
        SignInputService sign = new SignInputService(plugin, ShopConfig::defaults);
        service = new ShopService(plugin, plugin.shopRegistry(), economy,
                ShopConfig::defaults, Logger.getLogger("test"), buyLimits, sign);
    }

    @AfterEach
    void tearDown() {
        ShopGuiBuilder.setInventoryFactory(null);
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Shop shopOf(ShopItem item) {
        return new Shop("test", "&8Test", 54, 49, Map.of(item.id(), item));
    }

    private ShopItem item(String id, Material mat, String buy, String sell, int maxBuy) {
        return new ShopItem(id, mat, 1, ShopItem.NO_SLOT, 0, "", List.of(),
                buy == null ? BigDecimal.ZERO : new BigDecimal(buy),
                sell == null ? BigDecimal.ZERO : new BigDecimal(sell),
                buy != null, sell != null, SellMatch.PLAIN_MATERIAL, maxBuy);
    }

    private int count(Material mat) {
        int total = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s != null && s.getType() == mat) {
                total += s.getAmount();
            }
        }
        return total;
    }

    private ShopGuiHolder openHolder() {
        InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
        return holder instanceof ShopGuiHolder h ? h : null;
    }

    @Test
    void atThresholdExecutesDirectly() {
        ShopItem stone = item("stone", Material.STONE, "1", "1", 0);
        service.requestBuy(player, shopOf(stone), stone, 64, 0);
        assertEquals(64, count(Material.STONE));
        assertEquals(1, economy.withdrawCalls);
    }

    @Test
    void aboveThresholdOpensConfirmationWithoutAnyChange() {
        ShopItem stone = item("stone", Material.STONE, "1", "1", 0);
        service.requestBuy(player, shopOf(stone), stone, 65, 0);
        assertEquals(0, count(Material.STONE), "przed potwierdzeniem ekwipunek się nie zmienia");
        assertEquals(0, economy.withdrawCalls, "przed potwierdzeniem brak operacji ekonomii");
        ShopGuiHolder h = openHolder();
        assertTrue(h != null && h.view() == ShopGuiHolder.View.CONFIRM,
                "musi otworzyć widok potwierdzenia");
    }

    @Test
    void confirmExecutesExactlyOnce() {
        ShopItem stone = item("stone", Material.STONE, "1", "1", 0);
        Shop shop = shopOf(stone);
        plugin.shopRegistry().register(shop);
        service.confirmTransaction(player, shop, stone, ConfirmAction.BUY, 65, 0);
        assertEquals(65, count(Material.STONE));
        assertEquals(1, economy.withdrawCalls, "potwierdzenie wykonuje transakcję dokładnie raz");
    }

    @Test
    void cancelReturnsToDetailAndDoesNothing() {
        ShopItem stone = item("stone", Material.STONE, "1", "1", 0);
        service.cancelConfirmation(player, shopOf(stone), stone, 65, 0);
        assertEquals(0, economy.withdrawCalls);
        assertEquals(0, economy.depositCalls);
        ShopGuiHolder h = openHolder();
        assertTrue(h != null && h.view() == ShopGuiHolder.View.DETAIL,
                "anulowanie wraca do widoku szczegółów");
    }

    @Test
    void sellAllRecountedOnConfirm() {
        ShopItem stone = item("stone", Material.STONE, null, "5", 0);
        Shop shop = shopOf(stone);
        plugin.shopRegistry().register(shop);
        player.getInventory().addItem(new ItemStack(Material.STONE, 100));
        // Potwierdzenie pokazywało 100, ale przed potwierdzeniem stan spada.
        player.getInventory().clear();
        player.getInventory().addItem(new ItemStack(Material.STONE, 80));
        service.confirmTransaction(player, shop, stone, ConfirmAction.SELL_ALL, 100, 0);
        assertEquals(0, count(Material.STONE), "sprzedano faktyczny stan");
        assertEquals(new BigDecimal("400.00"), economy.lastDeposit, "cena z przeliczonych 80 sztuk (5*80)");
    }

    @Test
    void limitRecheckedOnConfirm() {
        ShopItem diamond = item("diamond", Material.DIAMOND, "500", "200", 64);
        Shop shop = shopOf(diamond);
        plugin.shopRegistry().register(shop);
        service.confirmTransaction(player, shop, diamond, ConfirmAction.BUY, 65, 0);
        assertEquals(0, count(Material.DIAMOND), "limit dzienny odrzuca 65 > 64");
        assertEquals(0, economy.withdrawCalls);
    }

    @Test
    void inventoryRecheckedOnConfirm() {
        int size = player.getInventory().getStorageContents().length;
        for (int i = 0; i < size; i++) {
            player.getInventory().setItem(i, new ItemStack(Material.OAK_SAPLING, 64));
        }
        ShopItem stone = item("stone", Material.STONE, "1", "1", 0);
        Shop shop = shopOf(stone);
        plugin.shopRegistry().register(shop);
        service.confirmTransaction(player, shop, stone, ConfirmAction.BUY, 65, 0);
        assertEquals(0, count(Material.STONE));
        assertEquals(0, economy.withdrawCalls, "pełny ekwipunek blokuje kupno przy potwierdzeniu");
    }
}
