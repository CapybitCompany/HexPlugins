package hex.auctionbazaar;

import hex.auctionbazaar.audit.repository.AuditLogRepository;
import hex.auctionbazaar.audit.service.AuditService;
import hex.auctionbazaar.auction.repository.AuctionClaimRepository;
import hex.auctionbazaar.auction.repository.AuctionListingRepository;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.bazaar.repository.BazaarOrderRepository;
import hex.auctionbazaar.bazaar.repository.BazaarStockRepository;
import hex.auctionbazaar.bazaar.service.BazaarOrderService;
import hex.auctionbazaar.bazaar.service.BazaarService;
import hex.auctionbazaar.bazaar.service.BazaarService.BuyResult;
import hex.auctionbazaar.bazaar.service.BazaarService.SellResult;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.bridge.HexCoreBridge;
import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.config.BazaarItemConfig;
import hex.auctionbazaar.config.MessagesConfig;
import hex.auctionbazaar.testutil.InMemoryDb;
import hex.auctionbazaar.testutil.TestConfigs;
import hex.auctionbazaar.util.MessageFactory;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkty #4/#5: autoryzacja i gate „enabled" są egzekwowane SERWEROWO - bezpośredni wywołanie
 * usługi bez uprawnień / przy wyłączonej funkcji NIE dotyka bazy (ani ekonomii/ekwipunku),
 * lecz od razu zwraca typizowany wynik odmowy.
 */
class ServiceAuthGateTest {

    private ServerMock server;
    private Plugin plugin;
    private final Logger log = Logger.getAnonymousLogger();
    private InMemoryDb db;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        db = new InMemoryDb();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) MockBukkit.unmock();
    }

    private BazaarConfig cfg(boolean enabled) {
        return new BazaarConfig(
                enabled, true, 14, 0L, 6000,
                new BazaarConfig.Pricing(new BigDecimal("0.5"), new BigDecimal("10000"),
                        new BigDecimal("5"), new BigDecimal("5")),
                "&8&lRynek", "&8%display%", "&8Q", "&8O", "&8Zlecenie", "GRAY_STAINED_GLASS_PANE",
                List.of(1L, 64L, 576L), false, 20,
                Map.<String, BazaarConfig.CategoryConfig>of(),
                "hexbazaar.open", "hexbazaar.buy", "hexbazaar.sell",
                "hexbazaar.orders", "hexbazaar.order.create.buy",
                "hexbazaar.order.create.sell", "hexbazaar.order.cancel", "hexbazaar.admin",
                Map.of("diamond", new BazaarItemConfig("diamond", Material.DIAMOND, "Diament", "ogólne",
                        new BigDecimal("10"), new BigDecimal("1"), new BigDecimal("1000"), 1000L, true, true)));
    }

    private BazaarService service(boolean enabled) {
        HexCoreBridge hexCore = new HexCoreBridge(log);
        EconomyBridge economy = new EconomyBridge(log);
        MessageFactory messages = new MessageFactory(() -> new MessagesConfig(Map.of()), () -> "");
        AuditService audit = new AuditService(log, hexCore, new AuditLogRepository(db), messages);
        BazaarOrderService orderService = new BazaarOrderService(plugin, hexCore, economy,
                new BazaarOrderRepository(db), new AuctionClaimRepository(db), audit,
                () -> cfg(enabled), () -> 14, () -> true);
        return new BazaarService(plugin, hexCore, economy, new BazaarStockRepository(db),
                new AuctionClaimRepository(db), audit, orderService, () -> cfg(enabled), () -> true,
                () -> true);
    }

    @Test
    void buyWithoutPermissionIsRejectedWithoutDbMutation() {
        PlayerMock p = server.addPlayer("NoPerms");   // brak hexbazaar.buy
        BazaarService svc = service(true);
        var f = svc.buy(p, "diamond", 5);
        assertTrue(f.isDone(), "gate zwraca synchronicznie (brak async przed sprawdzeniem)");
        assertEquals(BuyResult.NO_PERMISSION, f.join().result());
        assertTrue(db.operations().isEmpty(), "brak jakiejkolwiek mutacji/odczytu DB przy odmowie");
    }

    @Test
    void buyWhenDisabledIsRejectedWithoutDbMutation() {
        PlayerMock p = server.addPlayer("Buyer");
        p.addAttachment(plugin, "hexbazaar.buy", true);   // ma uprawnienie, ale funkcja wyłączona
        BazaarService svc = service(false);
        var f = svc.buy(p, "diamond", 5);
        assertTrue(f.isDone());
        assertEquals(BuyResult.FEATURE_DISABLED, f.join().result());
        assertTrue(db.operations().isEmpty(), "wyłączona funkcja nie dotyka DB");
    }

    @Test
    void sellWithoutPermissionIsRejectedWithoutDbMutation() {
        PlayerMock p = server.addPlayer("NoPerms");
        BazaarService svc = service(true);
        var f = svc.sell(p, "diamond", 5);
        assertTrue(f.isDone());
        assertEquals(SellResult.NO_PERMISSION, f.join().result());
        assertTrue(db.operations().isEmpty(), "sprzedaż bez uprawnień nie dotyka DB");
    }

    // ------------------------------------------------------------- usługi Aukcji / zleceń Rynku

    private AuctionService auctionService(boolean auctionEnabled, boolean pluginEnabled) {
        HexCoreBridge hexCore = new HexCoreBridge(log);
        EconomyBridge economy = new EconomyBridge(log);
        MessageFactory messages = new MessageFactory(() -> new MessagesConfig(Map.of()), () -> "");
        AuditService audit = new AuditService(log, hexCore, new AuditLogRepository(db), messages);
        return new AuctionService(plugin, hexCore, economy, new AuctionListingRepository(db),
                new AuctionClaimRepository(db), audit, () -> TestConfigs.auction(auctionEnabled),
                () -> pluginEnabled);
    }

    private BazaarOrderService orderService(boolean bazaarEnabled, boolean pluginEnabled) {
        HexCoreBridge hexCore = new HexCoreBridge(log);
        EconomyBridge economy = new EconomyBridge(log);
        MessageFactory messages = new MessageFactory(() -> new MessagesConfig(Map.of()), () -> "");
        AuditService audit = new AuditService(log, hexCore, new AuditLogRepository(db), messages);
        return new BazaarOrderService(plugin, hexCore, economy, new BazaarOrderRepository(db),
                new AuctionClaimRepository(db), audit, () -> TestConfigs.bazaar(bazaarEnabled), () -> 14,
                () -> pluginEnabled);
    }

    // ---- Auction SELL (wystawienie) ----

    @Test
    void auctionSellWithoutPermissionIsRejectedWithoutDbMutation() {
        PlayerMock p = server.addPlayer("NoPerms");   // brak hexauction.sell
        var f = auctionService(true, true).sellItemInHand(p, new BigDecimal("100"));
        assertTrue(f.isDone(), "gate synchroniczny (brak async przed odmową)");
        assertEquals(AuctionService.SellResult.NO_PERMISSION, f.join().result());
        assertTrue(db.operations().isEmpty(), "brak mutacji/odczytu DB");
    }

    @Test
    void auctionSellWhenAuctionDisabledIsFeatureDisabledNotNoItem() {
        PlayerMock p = server.addPlayer("Seller");
        p.addAttachment(plugin, "hexauction.sell", true);   // ma prawa, ale funkcja wyłączona
        var f = auctionService(false, true).sellItemInHand(p, new BigDecimal("100"));
        assertTrue(f.isDone());
        // NIE mylące NO_ITEM - deaktywowana sprzedaż to FEATURE_DISABLED (punkt #4).
        assertEquals(AuctionService.SellResult.FEATURE_DISABLED, f.join().result());
        assertTrue(db.operations().isEmpty());
    }

    @Test
    void auctionSellInMaintenanceIsFeatureDisabled() {
        PlayerMock p = server.addPlayer("Seller");
        p.addAttachment(plugin, "hexauction.sell", true);
        // Tryb konserwacji (pluginEnabled=false) blokuje serwerowo, mimo auction.enabled=true.
        var f = auctionService(true, false).sellItemInHand(p, new BigDecimal("100"));
        assertTrue(f.isDone());
        assertEquals(AuctionService.SellResult.FEATURE_DISABLED, f.join().result());
        assertTrue(db.operations().isEmpty());
    }

    // ---- Auction CANCEL (odzysk - permisja, ale bez bramki konserwacji) ----

    @Test
    void auctionCancelWithoutPermissionIsRejectedWithoutDbMutation() {
        PlayerMock p = server.addPlayer("NoPerms");   // brak hexauction.cancel
        var f = auctionService(true, true).cancel(p, 1L);
        assertTrue(f.isDone());
        assertEquals(AuctionService.CancelOutcome.NO_PERMISSION, f.join());
        assertTrue(db.operations().isEmpty());
    }

    // ---- Order BUY ----

    @Test
    void orderBuyWithoutPermissionIsRejectedWithoutDbMutation() {
        PlayerMock p = server.addPlayer("NoPerms");
        var f = orderService(true, true).placeBuyOrder(p, "diamond", 5, new BigDecimal("10"));
        assertTrue(f.isDone());
        assertEquals(BazaarOrderService.PlaceResult.NO_PERMISSION, f.join().result());
        assertTrue(db.operations().isEmpty());
    }

    @Test
    void orderBuyWhenDisabledIsFeatureDisabled() {
        PlayerMock p = server.addPlayer("Buyer");
        p.addAttachment(plugin, "hexbazaar.order.create.buy", true);
        var f = orderService(false, true).placeBuyOrder(p, "diamond", 5, new BigDecimal("10"));
        assertTrue(f.isDone());
        assertEquals(BazaarOrderService.PlaceResult.FEATURE_DISABLED, f.join().result());
        assertTrue(db.operations().isEmpty());
    }

    // ---- Order SELL ----

    @Test
    void orderSellWithoutPermissionIsRejectedWithoutDbMutation() {
        PlayerMock p = server.addPlayer("NoPerms");
        var f = orderService(true, true).placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        assertTrue(f.isDone());
        assertEquals(BazaarOrderService.PlaceResult.NO_PERMISSION, f.join().result());
        assertTrue(db.operations().isEmpty());
    }

    @Test
    void orderSellInMaintenanceIsFeatureDisabled() {
        PlayerMock p = server.addPlayer("Seller");
        p.addAttachment(plugin, "hexbazaar.order.create.sell", true);
        var f = orderService(true, false).placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        assertTrue(f.isDone());
        assertEquals(BazaarOrderService.PlaceResult.FEATURE_DISABLED, f.join().result());
        assertTrue(db.operations().isEmpty(), "wyłączona funkcja nie usuwa przedmiotów ani nie dotyka DB");
    }

    // ---- Order CANCEL + REMOVE (odzysk - permisja) ----

    @Test
    void orderCancelWithoutPermissionIsRejectedWithoutDbMutation() {
        PlayerMock p = server.addPlayer("NoPerms");
        var f = orderService(true, true).cancel(p, 1L);
        assertTrue(f.isDone());
        assertEquals(BazaarOrderService.CancelResult.NO_PERMISSION, f.join());
        assertTrue(db.operations().isEmpty());
    }

    @Test
    void removeCancelledWithoutPermissionIsRejectedWithoutDbMutation() {
        PlayerMock p = server.addPlayer("NoPerms");
        var f = orderService(true, true).removeCancelled(p, 1L);
        assertTrue(f.isDone());
        assertEquals(BazaarOrderService.RemoveResult.NO_PERMISSION, f.join());
        assertTrue(db.operations().isEmpty());
    }
}
