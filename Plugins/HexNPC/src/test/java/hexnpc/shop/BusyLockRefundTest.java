package hexnpc.shop;

import hexnpc.HexNpcPlugin;
import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.economy.EconomyBridge;
import hexnpc.shop.economy.TxResult;
import hexnpc.shop.model.SellMatch;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Weryfikuje, że blokada transakcji (busy) NIE jest zwalniana zanim
 * asynchroniczny refund po nieudanym wręczeniu itemu się zakończy.
 */
class BusyLockRefundTest {

    private ServerMock server;
    private HexNpcPlugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexNpcPlugin.class);
        player = server.addPlayer("Trader");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Shop buildShop(ShopItem item) {
        return new Shop("test", "&8Test", 27, 22, Map.of(item.id(), item));
    }

    private ShopItem singleStone() {
        return new ShopItem(
                "stone", Material.STONE, 1, 0,
                "", List.of(),
                new BigDecimal("10"), BigDecimal.ZERO,
                true, false, SellMatch.PLAIN_MATERIAL
        );
    }

    @Test
    void busyHeldUntilRefundCompletes() {
        ControllableEconomyBridge bridge = new ControllableEconomyBridge();
        ShopService service = new ShopService(plugin, plugin.shopRegistry(), bridge,
                () -> ShopConfig.defaults(), Logger.getLogger("test"));
        ShopItem item = singleStone();
        Shop shop = buildShop(item);

        // Withdraw zwraca natychmiast OK — to się policzy podczas buy().
        bridge.nextWithdraw = TxResult.ok(new BigDecimal("50"));

        // Inventory startuje puste, więc canFitFully na początku buy()
        // przejdzie. Trzeba je zapełnić ZANIM whenComplete-on-main-thread
        // dojdzie do giveAllOrNothing. Zrobimy to tak, że trzymamy
        // withdrawFuture jako incompleted i wypełniamy ekwipunek dopiero
        // przed jego ręcznym ukończeniem.
        CompletableFuture<TxResult> withdrawFuture = new CompletableFuture<>();
        bridge.nextWithdrawFuture = withdrawFuture;
        // Trzymamy deposit (refund) też jako incompleted aż do zaplanowanego momentu.
        CompletableFuture<TxResult> depositFuture = new CompletableFuture<>();
        bridge.nextDepositFuture = depositFuture;

        service.buy(player, shop, item);

        // Gracz jest oznaczony jako busy zaraz po buy().
        assertTrue(service.isBusy(player),
                "po wystartowaniu transakcji gracz powinien być busy");

        // Wypełniamy cały ekwipunek — bez tego giveAllOrNothing po
        // dokończeniu withdraw mogłoby się jeszcze udać.
        for (int i = 0; i < player.getInventory().getStorageContents().length; i++) {
            player.getInventory().setItem(i, new ItemStack(Material.OAK_SAPLING, 64));
        }

        // Kończymy withdraw — wewnątrz whenComplete tradeStack nie zmieści
        // się i poleci refundAfterFailedGive(...).
        withdrawFuture.complete(TxResult.ok(new BigDecimal("50")));

        // Refund jest jeszcze nieukończony — blokada musi nadal trzymać.
        assertTrue(service.isBusy(player),
                "podczas oczekiwania na refund gracz musi być nadal busy");

        // Dokończenie refundu zwalnia busy w finally onDone.
        depositFuture.complete(TxResult.ok(new BigDecimal("60")));
        assertFalse(service.isBusy(player),
                "po dokończeniu refundu busy musi zostać zwolnione");
    }

    /**
     * Stub bridge, w którym pojedyncza para futures jest sterowana
     * z testu. Każde wywołanie withdraw/deposit konsumuje ustawione
     * future albo zwraca natychmiastową odpowiedź.
     */
    private static final class ControllableEconomyBridge extends EconomyBridge {

        TxResult nextWithdraw = TxResult.ok(BigDecimal.ZERO);
        TxResult nextDeposit = TxResult.ok(BigDecimal.ZERO);
        CompletableFuture<TxResult> nextWithdrawFuture;
        CompletableFuture<TxResult> nextDepositFuture;

        ControllableEconomyBridge() {
            super(Logger.getLogger("controllable"));
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
            if (nextWithdrawFuture != null) {
                CompletableFuture<TxResult> f = nextWithdrawFuture;
                nextWithdrawFuture = null;
                return f;
            }
            return CompletableFuture.completedFuture(nextWithdraw);
        }

        @Override
        public CompletableFuture<TxResult> deposit(UUID uuid, String playerName,
                                                   BigDecimal amount, String reason) {
            if (nextDepositFuture != null) {
                CompletableFuture<TxResult> f = nextDepositFuture;
                nextDepositFuture = null;
                return f;
            }
            return CompletableFuture.completedFuture(nextDeposit);
        }
    }

    @Test
    void stubBridgeReportsAvailableSoBuyTriesEconomy() {
        // Sanity test, że stub bridge zachowuje się jak dostępny.
        ControllableEconomyBridge bridge = new ControllableEconomyBridge();
        assertNotNull(bridge);
        assertTrue(bridge.isAvailable());
    }
}
