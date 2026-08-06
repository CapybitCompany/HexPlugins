package hex.auctionbazaar;

import hex.auctionbazaar.audit.repository.AuditLogRepository;
import hex.auctionbazaar.audit.service.AuditService;
import hex.auctionbazaar.auction.repository.AuctionClaimRepository;
import hex.auctionbazaar.bazaar.repository.BazaarOrderRepository;
import hex.auctionbazaar.bazaar.service.BazaarOrderService;
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
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.math.BigDecimal;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #1: busy-guard per gracz przy składaniu zleceń. Dwa szybkie klik SELL nie mogą usunąć tych
 * samych przedmiotów dwa razy ani uruchomić dwóch równoległych count+insert (co przekroczyłoby limit).
 * Pierwszy klik trzyma guard, dopóki jego praca async jest „w locie" (tryb {@code PENDING_ASYNC}).
 */
class BazaarOrderBusyGuardTest {

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
        HexCoreBridge hexCore = TestHexApi.bootstrap(plugin,
                new TestDatabaseService(db, TestDatabaseService.Mode.PENDING_ASYNC));
        EconomyBridge economy = new EconomyBridge(log);   // SELL nie używa withdraw
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
    void twoFastSellClicksDoNotDoubleRemoveItems() {
        BazaarOrderService svc = orderService();
        PlayerMock p = server.addPlayer("Seller");
        p.addAttachment(plugin, "hexbazaar.order.create.sell", true);
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));

        // Pierwszy klik: przechodzi bramki, usuwa 5 diamentów, guard trzymany (count async „w locie").
        var f1 = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        assertFalse(f1.isDone(), "pierwsze zlecenie jest jeszcze przetwarzane (guard trzymany)");
        assertEquals(5, countDiamonds(p), "pierwszy klik usunął dokładnie 5 diamentów");

        // Drugi (podwójny) klik: guard trzymany -> BUSY synchronicznie, BEZ usuwania kolejnych przedmiotów.
        var f2 = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        assertTrue(f2.isDone(), "drugi klik odrzucony synchronicznie");
        assertEquals(PlaceResult.BUSY, f2.join().result());
        assertEquals(5, countDiamonds(p),
                "drugi klik NIE usunął kolejnych przedmiotów (brak podwójnego usunięcia)");

        // Drugi klik nigdy nie dotarł do count/insert -> nie ma dwóch równoległych ścieżek limitu.
        assertTrue(db.operations().isEmpty(),
                "drugi klik nie uruchomił count/insert (guard chroni limit zleceń)");
    }
}
