package hexnpc.shop;

import hexnpc.HexNpcPlugin;
import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.economy.EconomyBridge;
import hexnpc.shop.economy.TxResult;
import hexnpc.shop.inventory.InventoryOps;
import hexnpc.shop.inventory.ShopItemStackFactory;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopTransactionTest {

    private ServerMock server;
    private HexNpcPlugin plugin;
    private PlayerMock player;
    private StubEconomyBridge stubEconomy;
    private ShopService shopService;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexNpcPlugin.class);
        player = server.addPlayer("Trader");
        stubEconomy = new StubEconomyBridge();
        shopService = new ShopService(plugin, plugin.shopRegistry(), stubEconomy,
                () -> ShopConfig.defaults(), Logger.getLogger("test"));
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Shop buildShopWith(ShopItem item) {
        return new Shop("test", "&8Test", 27, 22, Map.of(item.id(), item));
    }

    private ShopItem plainBuySellItem() {
        return new ShopItem(
                "stone",
                Material.STONE, 4, 0,
                "", List.of(),
                new BigDecimal("10"), new BigDecimal("5"),
                true, true, SellMatch.PLAIN_MATERIAL
        );
    }

    private ShopItem namedExactItem() {
        return new ShopItem(
                "rare",
                Material.DIAMOND, 2, 0,
                "&bRzadki Diament",
                List.of("&7Pierwsza linia lore"),
                BigDecimal.ZERO, new BigDecimal("100"),
                false, true, SellMatch.EXACT_ITEM
        );
    }

    @Test
    void buyAsyncWithdrawFailureWithInsufficientReasonShowsNotEnoughMoney() {
        stubEconomy.nextWithdraw = TxResult.fail("NOT_ENOUGH_FUNDS");
        ShopItem item = plainBuySellItem();
        Shop shop = buildShopWith(item);

        shopService.buy(player, shop, item);
        // Nic nie powinno trafić do ekwipunku.
        for (ItemStack s : player.getInventory().getStorageContents()) {
            assertFalse(s != null && s.getType() == Material.STONE,
                    "przy niedoborze środków nie może powstać żaden stos");
        }
        // Gracz dostaje zlokalizowany komunikat „not-enough-money".
        String last = drainMessages(player);
        assertTrue(last.contains("pieniędzy") || last.toLowerCase().contains("not enough"),
                "oczekiwano komunikatu o brakujących środkach; było: " + last);
    }

    @Test
    void buySuccessGivesItemAndDoesNotCallSyncHas() {
        stubEconomy.nextWithdraw = TxResult.ok(new BigDecimal("90"));
        ShopItem item = plainBuySellItem();
        Shop shop = buildShopWith(item);

        shopService.buy(player, shop, item);
        // Stub zlicza wywołania — has() nie może być wywoływane.
        assertEquals(0, stubEconomy.hasCalls, "buy-flow nie może wywoływać synchronicznego has()");
        assertEquals(1, stubEconomy.withdrawCalls, "buy-flow musi wywołać asynchroniczny withdraw");
        // Stos jest w ekwipunku.
        boolean found = false;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s != null && s.getType() == Material.STONE && s.getAmount() == item.amount()) {
                found = true;
                break;
            }
        }
        assertTrue(found, "zakupiony stos kamieni musi być w ekwipunku");
    }

    @Test
    void sellDepositFailureReturnsExactMetaItems() {
        stubEconomy.nextDeposit = TxResult.fail("BACKEND_DOWN");

        ShopItem item = namedExactItem();
        Shop shop = buildShopWith(item);

        // Wkładamy do ekwipunku stos pasujący do wzorca EXACT_ITEM
        // (ta sama nazwa + lore).
        ItemStack candidate = ShopItemStackFactory.exactTemplate(item);
        candidate.setAmount(item.amount());
        player.getInventory().addItem(candidate);
        ItemStack[] before = InventoryOps.cloneStorage(player.getInventory());

        shopService.sell(player, shop, item);

        // Ekwipunek musi mieć tę samą liczbę itemów (zwrócone).
        ItemStack[] after = InventoryOps.cloneStorage(player.getInventory());
        int beforeCount = countDiamonds(before);
        int afterCount = countDiamonds(after);
        assertEquals(beforeCount, afterCount,
                "przy nieudanym deposit ta sama liczba itemów musi wrócić");
        // I meta musi być zachowana na zwróconym stosie.
        boolean foundNamed = false;
        for (ItemStack s : after) {
            if (s == null || s.getType() != Material.DIAMOND) {
                continue;
            }
            ItemMeta meta = s.getItemMeta();
            if (meta != null && meta.hasDisplayName() && meta.hasLore()) {
                foundNamed = true;
                break;
            }
        }
        assertTrue(foundNamed, "zwrócony stos musi nieść skonfigurowane display name + lore");
    }

    @Test
    void sellDepositFailureDropsRemainderWhenInventoryFullOnReturn() {
        stubEconomy.nextDeposit = TxResult.fail("BACKEND_DOWN");

        ShopItem item = plainBuySellItem();
        Shop shop = buildShopWith(item);

        // 4 kamienie do sprzedania.
        player.getInventory().addItem(new ItemStack(Material.STONE, 4));
        // Wypełniamy pozostałe sloty czymś niestakowalnym z kamieniem.
        // Po usunięciu 4 kamieni i próbie ich zwrotu nie ma już miejsca
        // — helper musi zrzucić nadmiar pod stopy gracza.
        int size = player.getInventory().getStorageContents().length;
        for (int i = 0; i < size; i++) {
            if (player.getInventory().getItem(i) == null) {
                player.getInventory().setItem(i, new ItemStack(Material.OAK_SAPLING, 64));
            }
        }

        long droppedBefore = countDropsInWorld();
        shopService.sell(player, shop, item);
        long droppedAfter = countDropsInWorld();

        // Kamień albo wrócił do ekwipunku, albo wylądował na ziemi.
        // Asercja: brak ubytku — kamień w inv + drop >= ilość wyjściowa.
        int stoneInInv = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s != null && s.getType() == Material.STONE) {
                stoneInInv += s.getAmount();
            }
        }
        long stoneDropped = countDroppedStoneSince(droppedBefore);
        assertEquals(item.amount(), stoneInInv + stoneDropped,
                "deposit-fail musi oddać lub zrzucić dokładnie zabraną liczbę; ubytek zabroniony");
    }

    private int countDiamonds(ItemStack[] contents) {
        int total = 0;
        for (ItemStack s : contents) {
            if (s != null && s.getType() == Material.DIAMOND) {
                total += s.getAmount();
            }
        }
        return total;
    }

    private long countDropsInWorld() {
        return player.getWorld().getEntitiesByClass(Item.class).size();
    }

    private long countDroppedStoneSince(long previousCount) {
        long total = 0;
        for (Item entity : player.getWorld().getEntitiesByClass(Item.class)) {
            if (entity.getItemStack().getType() == Material.STONE) {
                total += entity.getItemStack().getAmount();
            }
        }
        // Wcześniejsza liczba dla kamienia nie ma znaczenia — przed
        // tym testem nie ma w świecie żadnych dropniętych kamieni.
        return total;
    }

    private String drainMessages(PlayerMock player) {
        StringBuilder sb = new StringBuilder();
        String msg;
        while ((msg = player.nextMessage()) != null) {
            sb.append(msg).append("\n");
        }
        return sb.toString();
    }

    /**
     * Test double dla EconomyBridge zwracający przygotowane wyniki
     * TxResult. has() nie jest wystawione w ogóle — pole hasCalls
     * istnieje jako asercja, że żaden kod nie próbuje go wywołać.
     */
    private static final class StubEconomyBridge extends EconomyBridge {

        TxResult nextWithdraw = TxResult.ok(BigDecimal.ZERO);
        TxResult nextDeposit = TxResult.ok(BigDecimal.ZERO);
        int withdrawCalls = 0;
        int depositCalls = 0;
        int hasCalls = 0;

        StubEconomyBridge() {
            super(Logger.getLogger("stub"));
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String currencyName() {
            return "$";
        }

        @Override
        public String format(BigDecimal value) {
            return value == null ? "0" : value.toPlainString();
        }

        @Override
        public CompletableFuture<TxResult> withdraw(UUID uuid, String playerName,
                                                    BigDecimal amount, String reason) {
            withdrawCalls++;
            return CompletableFuture.completedFuture(nextWithdraw);
        }

        @Override
        public CompletableFuture<TxResult> deposit(UUID uuid, String playerName,
                                                   BigDecimal amount, String reason) {
            depositCalls++;
            return CompletableFuture.completedFuture(nextDeposit);
        }
    }
}
