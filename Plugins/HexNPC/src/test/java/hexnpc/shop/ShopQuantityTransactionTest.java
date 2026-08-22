package hexnpc.shop;

import hexnpc.HexNpcPlugin;
import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.economy.TxResult;
import hexnpc.shop.limit.DailyBuyLimitService;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.Shop;
import hexnpc.shop.gui.ShopGuiBuilder;
import hexnpc.shop.model.ShopItem;
import hexnpc.shop.sign.SignInputService;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transakcje z wybraną ilością: presety, wielo-stosowe kupno, itemy
 * niestakowalne, sprzedaż i „sprzedaj wszystko", dzienny limit kupna oraz
 * bezpieczeństwo transakcji (pełny ekwipunek, refund, blokada busy).
 */
class ShopQuantityTransactionTest {

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
        SignInputService signService = new SignInputService(plugin, ShopConfig::defaults);
        service = new ShopService(plugin, plugin.shopRegistry(), economy,
                ShopConfig::defaults, Logger.getLogger("test"), buyLimits, signService);
    }

    @AfterEach
    void tearDown() {
        ShopGuiBuilder.setInventoryFactory(null);
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    // --- helpers ---

    private Shop shopOf(ShopItem item) {
        return new Shop("test", "&8Test", 54, 49, Map.of(item.id(), item));
    }

    private ShopItem item(String id, Material mat, int amount, String buy, String sell, int maxBuy) {
        return new ShopItem(id, mat, amount, ShopItem.NO_SLOT, 0, "", List.of(),
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

    private void fillInventory() {
        int size = player.getInventory().getStorageContents().length;
        for (int i = 0; i < size; i++) {
            player.getInventory().setItem(i, new ItemStack(Material.OAK_SAPLING, 64));
        }
    }

    private String drain() {
        StringBuilder sb = new StringBuilder();
        String m;
        while ((m = player.nextMessage()) != null) {
            sb.append(m).append('\n');
        }
        return sb.toString();
    }

    // --- Presety / ilości ---

    @Test
    void buyPreset1ChargesProportionalPrice() {
        ShopItem cobble = item("cobble", Material.COBBLESTONE, 64, "100.00", "25.00", 0);
        service.buy(player, shopOf(cobble), cobble, 1);
        assertEquals(1, count(Material.COBBLESTONE));
        assertEquals(new BigDecimal("1.56"), economy.lastWithdraw); // 100/64 -> 1.56
    }

    @Test
    void buyPreset64ChargesConfiguredPrice() {
        ShopItem cobble = item("cobble", Material.COBBLESTONE, 64, "100.00", "25.00", 0);
        service.buy(player, shopOf(cobble), cobble, 64);
        assertEquals(64, count(Material.COBBLESTONE));
        assertEquals(new BigDecimal("100.00"), economy.lastWithdraw);
    }

    @Test
    void buyAboveStackSizeSplitsIntoStacks() {
        ShopItem stone = item("stone", Material.STONE, 1, "1", "1", 0);
        service.buy(player, shopOf(stone), stone, 128);
        assertEquals(128, count(Material.STONE));
        assertEquals(new BigDecimal("128.00"), economy.lastWithdraw);
    }

    @Test
    void buyNonStackableItems() {
        ShopItem sword = item("sword", Material.DIAMOND_SWORD, 1, "100", null, 0);
        service.buy(player, shopOf(sword), sword, 3);
        assertEquals(3, count(Material.DIAMOND_SWORD));
    }

    @Test
    void sellQuantityRemovesExactlyAndPaysProportional() {
        ShopItem stone = item("stone", Material.STONE, 1, null, "5", 0);
        player.getInventory().addItem(new ItemStack(Material.STONE, 100));
        service.sell(player, shopOf(stone), stone, 40);
        assertEquals(60, count(Material.STONE));
        assertEquals(new BigDecimal("200.00"), economy.lastDeposit); // 5 * 40
    }

    @Test
    void sellAllSellsEverythingMatching() {
        ShopItem stone = item("stone", Material.STONE, 1, null, "5", 0);
        player.getInventory().addItem(new ItemStack(Material.STONE, 100));
        service.sellAll(player, shopOf(stone), stone);
        assertEquals(0, count(Material.STONE));
        assertEquals(1, economy.depositCalls);
        assertEquals(new BigDecimal("500.00"), economy.lastDeposit); // 5 * 100
    }

    // --- Limity dzienne ---

    @Test
    void buyUnderLimitSucceedsAndRecords() {
        ShopItem diamond = item("diamond", Material.DIAMOND, 1, "500", "200", 64);
        Shop shop = shopOf(diamond);
        service.buy(player, shop, diamond, 32);
        assertEquals(32, count(Material.DIAMOND));
        assertEquals(32, buyLimits.purchasedToday(player.getUniqueId(),
                DailyBuyLimitService.key("test", "diamond")));
    }

    @Test
    void buyExactlyAtLimitSucceeds() {
        ShopItem diamond = item("diamond", Material.DIAMOND, 1, "500", "200", 64);
        service.buy(player, shopOf(diamond), diamond, 64);
        assertEquals(64, count(Material.DIAMOND));
    }

    @Test
    void buyOverLimitIsRejected() {
        ShopItem diamond = item("diamond", Material.DIAMOND, 1, "500", "200", 64);
        service.buy(player, shopOf(diamond), diamond, 65);
        assertEquals(0, count(Material.DIAMOND));
        assertEquals(0, economy.withdrawCalls, "przy przekroczeniu limitu nie może dojść do withdraw");
        assertTrue(drain().toLowerCase().contains("maksymalnie"),
                "gracz musi dostać komunikat o limicie");
    }

    @Test
    void buyLimitAccumulatesAcrossTransactions() {
        ShopItem diamond = item("diamond", Material.DIAMOND, 1, "500", "200", 64);
        Shop shop = shopOf(diamond);
        service.buy(player, shop, diamond, 40);
        assertEquals(1, economy.withdrawCalls);
        // 40 + 40 > 64 -> druga transakcja odrzucona.
        service.buy(player, shop, diamond, 40);
        assertEquals(1, economy.withdrawCalls, "druga transakcja ponad limit nie może pobrać środków");
        assertEquals(40, count(Material.DIAMOND));
    }

    @Test
    void unlimitedBuyWithoutLimitConfig() {
        ShopItem stone = item("stone", Material.STONE, 1, "1", "1", 0);
        service.buy(player, shopOf(stone), stone, 500);
        assertEquals(500, count(Material.STONE));
    }

    @Test
    void sellIgnoresBuyLimit() {
        ShopItem diamond = item("diamond", Material.DIAMOND, 1, "500", "200", 10);
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 50));
        service.sell(player, shopOf(diamond), diamond, 50);
        assertEquals(0, count(Material.DIAMOND), "sprzedaż nie jest ograniczona limitem kupna");
        assertEquals(new BigDecimal("10000.00"), economy.lastDeposit); // 200 * 50
    }

    @Test
    void sellAllIgnoresBuyLimit() {
        ShopItem diamond = item("diamond", Material.DIAMOND, 1, "500", "200", 10);
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 30));
        service.sellAll(player, shopOf(diamond), diamond);
        assertEquals(0, count(Material.DIAMOND));
    }

    // --- Bezpieczeństwo transakcji ---

    @Test
    void fullInventoryBeforeBuyRefusesWithoutWithdraw() {
        fillInventory();
        ShopItem stone = item("stone", Material.STONE, 1, "1", "1", 0);
        service.buy(player, shopOf(stone), stone, 1);
        assertEquals(0, count(Material.STONE));
        assertEquals(0, economy.withdrawCalls);
    }

    @Test
    void multiStackThatDoesNotFitIsRefused() {
        // Zostawiamy jeden wolny slot; 128 kamieni potrzebuje dwóch.
        int size = player.getInventory().getStorageContents().length;
        for (int i = 0; i < size - 1; i++) {
            player.getInventory().setItem(i, new ItemStack(Material.OAK_SAPLING, 64));
        }
        ShopItem stone = item("stone", Material.STONE, 1, "1", "1", 0);
        service.buy(player, shopOf(stone), stone, 128);
        assertEquals(0, count(Material.STONE));
        assertEquals(0, economy.withdrawCalls, "brak miejsca na pełną ilość musi zablokować withdraw");
    }

    @Test
    void inventoryFillsDuringAsyncWithdrawTriggersRefundAndHoldsBusy() {
        ShopItem stone = item("stone", Material.STONE, 1, "10", "5", 0);
        Shop shop = shopOf(stone);
        CompletableFuture<TxResult> withdrawFuture = new CompletableFuture<>();
        CompletableFuture<TxResult> depositFuture = new CompletableFuture<>();
        economy.nextWithdrawFuture = withdrawFuture;
        economy.nextDepositFuture = depositFuture;

        service.buy(player, shop, stone, 1);
        assertTrue(service.isBusy(player), "po starcie transakcji gracz jest busy");

        fillInventory();
        withdrawFuture.complete(TxResult.ok(new BigDecimal("50")));

        // Refund (deposit) jeszcze nieukończony -> busy nadal trzyma.
        assertTrue(service.isBusy(player), "podczas refundu busy musi trzymać");
        assertEquals(1, economy.depositCalls, "nieudane wydanie musi uruchomić refund");
        assertEquals(0, count(Material.STONE), "przy pełnym ekwipunku żaden kamień nie może zostać wydany");

        depositFuture.complete(TxResult.ok(new BigDecimal("60")));
        assertFalse(service.isBusy(player), "po refundzie busy musi być zwolnione");
    }

    @Test
    void twoFastClicksCannotDoubleTransact() {
        ShopItem stone = item("stone", Material.STONE, 1, "10", "5", 0);
        Shop shop = shopOf(stone);
        CompletableFuture<TxResult> withdrawFuture = new CompletableFuture<>();
        economy.nextWithdrawFuture = withdrawFuture;

        service.buy(player, shop, stone, 1);
        assertTrue(service.isBusy(player));
        assertEquals(1, economy.withdrawCalls);

        // Drugi klik w trakcie trwającej transakcji.
        service.buy(player, shop, stone, 1);
        assertEquals(1, economy.withdrawCalls, "drugi klik nie może wystartować drugiej transakcji");
        assertTrue(drain().toLowerCase().contains("poczekaj"), "gracz musi zobaczyć komunikat busy");

        withdrawFuture.complete(TxResult.ok(new BigDecimal("90")));
        assertFalse(service.isBusy(player));
        assertEquals(1, count(Material.STONE));
    }

    @Test
    void customQuantityInputInvalidReopensWithMessage() {
        ShopItem stone = item("stone", Material.STONE, 1, "10", "5", 0);
        Shop shop = shopOf(stone);
        plugin.shopRegistry().register(shop); // udostępnij w rejestrze dla lookupu
        service.handleCustomQuantityInput(player.getUniqueId(), "test", "stone", 0, "abc");
        assertTrue(drain().toLowerCase().contains("nieprawidłowa"),
                "nieprawidłowa ilość musi dać polski komunikat");
    }

    @Test
    void customQuantityInputValidIsAccepted() {
        ShopItem stone = item("stone", Material.STONE, 1, "10", "5", 0);
        Shop shop = shopOf(stone);
        plugin.shopRegistry().register(shop);
        // Nie powinno rzucić; wybrana ilość zostaje przyjęta i widok wraca.
        service.handleCustomQuantityInput(player.getUniqueId(), "test", "stone", 0, "50");
    }
}
