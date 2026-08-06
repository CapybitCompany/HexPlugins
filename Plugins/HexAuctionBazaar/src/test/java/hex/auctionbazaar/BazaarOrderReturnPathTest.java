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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #1: gdy zapis SELL-oferty się nie powiedzie, wystawione przedmioty wracają do ekwipunku
 * BATCHOWO (all-or-nothing). Sprawdzamy PRODUKCYJNĄ ścieżkę placeSellOffer -> insert fails ->
 * returnStacksTracked: przedmioty wracają DOKŁADNIE raz (brak duplikacji, brak world-dropu),
 * a wynik to zwykły błąd DB_FAILED (bezpieczny zwrot), nie COMPENSATION_FAILED.
 */
class BazaarOrderReturnPathTest {

    private ServerMock server;
    private Plugin plugin;
    private InMemoryDb db;
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

    private BazaarOrderService orderService() {
        HexCoreBridge hexCore = TestHexApi.bootstrap(plugin, new TestDatabaseService(db));
        EconomyBridge economy = new EconomyBridge(log);
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
    void sellInsertFailureReturnsItemsToInventoryExactlyOnce() {
        db.failUpdatesContaining("hex_bazaar_orders");   // zapis SELL-oferty się nie powiedzie
        BazaarOrderService svc = orderService();
        PlayerMock p = server.addPlayer("Seller");
        p.addAttachment(plugin, "hexbazaar.order.create.sell", true);
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));

        var future = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        // Zwrot dzieje się na wątku głównym (onMain) - pompujemy scheduler MockBukkit.
        server.getScheduler().performTicks(5L);

        PlaceOutcome outcome = future.join();
        // Bezpieczny zwrot -> zwykły błąd DB_FAILED, NIE COMPENSATION_FAILED.
        assertEquals(PlaceResult.DB_FAILED, outcome.result());
        // Przedmioty wróciły DOKŁADNIE raz: 5 (nie 0 = zgubione, nie 10 = zduplikowane).
        assertEquals(5, countDiamonds(p), "wystawione przedmioty wróciły do ekwipunku dokładnie raz");
        // Zwrot trafił do EKWIPUNKU (jest miejsce), więc NIE powstał żaden item-claim.
        boolean anyClaimInsert = db.operations().stream()
                .anyMatch(op -> op.sql().contains("INSERT INTO") && op.sql().contains("hex_auction_claims"));
        assertFalse(anyClaimInsert, "zwrot do ekwipunku nie tworzy claim-a (brak duplikacji)");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void offlineSellerReturnIsPersistedAsAtomicClaim() {
        db.failUpdatesContaining("hex_bazaar_orders");   // zapis SELL-oferty się nie powiedzie
        BazaarOrderService svc = orderService();
        PlayerMock p = server.addPlayer("Seller");
        p.addAttachment(plugin, "hexbazaar.order.create.sell", true);
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));

        var future = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        // Gracz wylogowuje się w oknie async (przed zwrotem) -> zwrot musi trafić do claim-a, nie world-drop.
        p.disconnect();
        server.getScheduler().performTicks(5L);

        PlaceOutcome outcome = future.join();
        assertEquals(PlaceResult.DB_FAILED, outcome.result(), "bezpieczny zwrot (jako claim) -> DB_FAILED");
        boolean claimInsert = db.operations().stream()
                .anyMatch(op -> op.sql().contains("INSERT INTO") && op.sql().contains("hex_auction_claims"));
        assertTrue(claimInsert, "przedmioty wystawionej oferty zapisane jako item-claim (odbiór)");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void failedClaimInsertYieldsCompensationFailedNotFalseOk() {
        db.failUpdatesContaining("INSERT INTO");   // zawiedzie i order-insert, i claim-insert
        BazaarOrderService svc = orderService();
        PlayerMock p = server.addPlayer("Seller");
        p.addAttachment(plugin, "hexbazaar.order.create.sell", true);
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));

        var future = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        p.disconnect();   // offline -> ścieżka claim; ale claim-insert też pada -> stan krytyczny
        server.getScheduler().performTicks(5L);

        // Nieudany atomowy claim -> COMPENSATION_FAILED (nigdy fałszywe „OK"/zwykły błąd).
        assertEquals(PlaceResult.COMPENSATION_FAILED, future.join().result());
    }

    @Test
    void returnOutcomeSafetyMappingIsExplicit() {
        // ADDED_FULLY/CLAIMED = zwrot bezpieczny -> zwykły błąd (normalFail). STATE_UNCERTAIN i CLAIM_FAILED
        // = NIE bezpieczny -> COMPENSATION_FAILED. KLUCZOWE: STATE_UNCERTAIN nie tworzy claim (brak duplikacji).
        assertTrue(BazaarOrderService.ReturnOutcome.ADDED_FULLY.safe());
        assertTrue(BazaarOrderService.ReturnOutcome.CLAIMED.safe());
        assertFalse(BazaarOrderService.ReturnOutcome.STATE_UNCERTAIN.safe(),
                "niepewny stan ekwipunku nie jest bezpieczny -> COMPENSATION_FAILED, BEZ claim");
        assertFalse(BazaarOrderService.ReturnOutcome.CLAIM_FAILED.safe());
    }
}
