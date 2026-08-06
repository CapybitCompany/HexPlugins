package hex.auctionbazaar;

import hex.auctionbazaar.audit.repository.AuditLogRepository;
import hex.auctionbazaar.audit.service.AuditService;
import hex.auctionbazaar.auction.repository.AuctionClaimRepository;
import hex.auctionbazaar.bazaar.repository.BazaarOrderRepository;
import hex.auctionbazaar.bazaar.service.BazaarOrderService;
import hex.auctionbazaar.bazaar.service.BazaarOrderService.PlaceOutcome;
import hex.auctionbazaar.bazaar.service.BazaarOrderService.PlaceResult;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.bridge.HexCoreBridge;
import hex.auctionbazaar.config.MessagesConfig;
import hex.auctionbazaar.testutil.InMemoryDb;
import hex.auctionbazaar.testutil.TestConfigs;
import hex.auctionbazaar.testutil.TestDatabaseService;
import hex.auctionbazaar.testutil.TestHexApi;
import hex.auctionbazaar.util.MessageFactory;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #1: składanie SELL-oferty jest w KAŻDEJ ścieżce TERMINALNE, a busy-guard zawsze zwalniany.
 * Sprawdzamy realny produkcyjny placeSellOffer z synchronicznie rzucającym {@code hexCore.async} (symulacja
 * odrzucenia executora przy count) - przedmioty muszą wrócić do ekwipunku, future musi się domknąć,
 * a guard musi zostać zwolniony (kolejne wejście nie dostaje BUSY).
 */
class BazaarSellOfferTerminalTest {

    private ServerMock server;
    private Plugin plugin;
    private InMemoryDb db;
    private TestDatabaseService dbService;
    private final Logger log = Logger.getAnonymousLogger();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        db = new InMemoryDb();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private BazaarOrderService orderService(TestDatabaseService.Mode mode) {
        dbService = new TestDatabaseService(db, mode);
        HexCoreBridge hexCore = TestHexApi.bootstrap(plugin, dbService);
        EconomyBridge economy = new EconomyBridge(log);   // SELL nie używa withdraw/deposit
        MessageFactory messages = new MessageFactory(() -> new MessagesConfig(Map.of()), () -> "");
        AuditService audit = new AuditService(log, hexCore, new AuditLogRepository(db), messages);
        return new BazaarOrderService(plugin, hexCore, economy, new BazaarOrderRepository(db),
                new AuctionClaimRepository(db), audit, () -> TestConfigs.bazaar(true), () -> 14, () -> true);
    }

    private static int countDiamonds(PlayerMock p) {
        int total = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s != null && s.getType() == Material.DIAMOND) {
                total += s.getAmount();
            }
        }
        return total;
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void syncAsyncRejectionReturnsItemsAndReleasesBusyGuard() {
        // hexCore.async rzuca synchronicznie na count (symulacja odrzucenia przez executor przy wyłączaniu).
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.THROW_ON_ASYNC);
        PlayerMock p = server.addPlayer("Seller");
        p.addAttachment(plugin, "hexbazaar.order.create.sell", true);
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));

        var f1 = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        server.getScheduler().performTicks(5L);   // zwrot dzieje się na wątku głównym (onMain)

        PlaceOutcome o1 = f1.join();
        assertTrue(f1.isDone(), "future domknięty mimo synchronicznego wyjątku async");
        assertEquals(PlaceResult.DB_FAILED, o1.result(), "bezpieczny zwrot -> zwykły błąd, nie COMPENSATION_FAILED");
        assertEquals(5, countDiamonds(p), "wystawione przedmioty wróciły do ekwipunku dokładnie raz");

        // Busy-guard zwolniony: drugie wejście NIE dostaje BUSY (znów przechodzi realną ścieżkę).
        var f2 = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        server.getScheduler().performTicks(5L);
        PlaceOutcome o2 = f2.join();
        assertNotEquals(PlaceResult.BUSY, o2.result(), "guard zwolniony po terminalnym wyniku pierwszego wejścia");
        assertEquals(PlaceResult.DB_FAILED, o2.result());
        assertEquals(5, countDiamonds(p), "i tym razem przedmioty wróciły");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void notEnoughItemsIsTerminalAndReleasesGuard() {
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.DIRECT);
        PlayerMock p = server.addPlayer("Seller");
        p.addAttachment(plugin, "hexbazaar.order.create.sell", true);
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 2));   // za mało na 5

        var f1 = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        assertTrue(f1.isDone(), "za mało przedmiotów -> synchroniczny, terminalny wynik");
        assertEquals(PlaceResult.NOT_ENOUGH_ITEMS, f1.join().result());
        assertEquals(2, countDiamonds(p), "nic nie zdjęto");

        // Guard zwolniony: kolejne wejście z wystarczającą ilością przechodzi (nie BUSY).
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 3));   // razem 5
        var f2 = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        server.getScheduler().performTicks(5L);
        assertNotEquals(PlaceResult.BUSY, f2.join().result(), "guard zwolniony po NOT_ENOUGH_ITEMS");
        assertEquals(PlaceResult.OK, f2.join().result(), "poprawne wystawienie po uzupełnieniu");
    }
}
