package hex.auctionbazaar.bazaar.service;

import hex.auctionbazaar.audit.repository.AuditLogRepository;
import hex.auctionbazaar.audit.service.AuditService;
import hex.auctionbazaar.auction.repository.AuctionClaimRepository;
import hex.auctionbazaar.bazaar.repository.BazaarOrderRepository;
import hex.auctionbazaar.bazaar.service.BazaarOrderService.PlaceOutcome;
import hex.auctionbazaar.bazaar.service.BazaarOrderService.PlaceResult;
import hex.auctionbazaar.bazaar.service.BazaarOrderService.ReturnRecoverySeam;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.bridge.HexCoreBridge;
import hex.auctionbazaar.config.MessagesConfig;
import hex.auctionbazaar.testutil.InMemoryDb;
import hex.auctionbazaar.testutil.TestConfigs;
import hex.auctionbazaar.testutil.TestDatabaseService;
import hex.auctionbazaar.testutil.TestHexApi;
import hex.auctionbazaar.util.InventoryFit;
import hex.auctionbazaar.util.ItemSerializer;
import hex.auctionbazaar.util.MessageFactory;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Odzysk zwrotu wystawionych przedmiotów SELL: KAŻDY zdjęty stack ma dokładnie jeden terminalny ausgang
 * (zwrot do ekwipunku / atomowy claim / COMPENSATION_FAILED), nigdy: cichej straty, jednoczesnego
 * ekwipunku+claim, hängender Future ani nieodblokowanego busy-guarda. Determinizm przez wstrzykiwany
 * {@link ReturnRecoverySeam} (dispatch wątku głównego + próba zwrotu do ekwipunku). Bez sleepów, bez
 * {@code @Disabled}.
 */
class BazaarOrderReturnRecoveryTest {

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

    /** Seam: planowanie (RUN natychmiast / QUEUE bez wykonania / REJECT rzuca) + wymuszalny wynik zwrotu. */
    private static final class TestSeam implements ReturnRecoverySeam {
        enum Mode { RUN, QUEUE, REJECT }
        volatile Mode mode = Mode.RUN;
        final List<Runnable> queued = new CopyOnWriteArrayList<>();
        volatile InventoryFit.Result forcedResult = null;   // null -> realny InventoryFit

        @Override
        public void dispatchMain(Runnable task) {
            switch (mode) {
                case RUN -> task.run();
                case QUEUE -> queued.add(task);
                case REJECT -> throw new RejectedExecutionException("test reject");
            }
        }

        @Override
        public InventoryFit.Result tryReturnToInventory(Player seller, List<ItemStack> stacks) {
            return forcedResult != null ? forcedResult : InventoryFit.tryAddAllFull(seller, stacks);
        }

        void runQueued() {
            List<Runnable> copy = new ArrayList<>(queued);
            queued.clear();
            for (Runnable r : copy) {
                r.run();
            }
        }
    }

    private BazaarOrderService orderService(TestDatabaseService.Mode dbMode, ReturnRecoverySeam seam) {
        dbService = new TestDatabaseService(db, dbMode);
        HexCoreBridge hexCore = TestHexApi.bootstrap(plugin, dbService);
        EconomyBridge economy = new EconomyBridge(log);   // SELL nie używa ekonomii
        MessageFactory messages = new MessageFactory(() -> new MessagesConfig(Map.of()), () -> "");
        AuditService audit = new AuditService(log, hexCore, new AuditLogRepository(db), messages);
        return new BazaarOrderService(plugin, hexCore, economy, new BazaarOrderRepository(db),
                new AuctionClaimRepository(db), audit, () -> TestConfigs.bazaar(true), () -> 14,
                () -> true, seam);
    }

    private PlayerMock seller(int diamonds) {
        PlayerMock p = server.addPlayer("Seller");
        p.addAttachment(plugin, "hexbazaar.order.create.sell", true);
        if (diamonds > 0) {
            p.getInventory().addItem(new ItemStack(Material.DIAMOND, diamonds));
        }
        return p;
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

    private static int claimInsertCount(InMemoryDb db) {
        return (int) db.operations().stream()
                .filter(op -> op.sql().contains("INSERT INTO") && op.sql().contains("hex_auction_claims"))
                .count();
    }

    /** Guard zwolniony <=> drugie wejście przechodzi bramkę BUSY (sprzedaż > stanu daje NOT_ENOUGH_ITEMS). */
    private void assertGuardReleased(BazaarOrderService svc, PlayerMock p) {
        PlaceOutcome o = svc.placeSellOffer(p, "diamond", 100_000, new BigDecimal("10")).join();
        assertEquals(PlaceResult.NOT_ENOUGH_ITEMS, o.result(),
                "busy-guard zwolniony (drugie wejście przechodzi -> NOT_ENOUGH_ITEMS, a nie BUSY)");
    }

    // ---------------------------------------------------------------- 1) odrzucenie planowania + claim OK

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void schedulerRejectionWithSuccessfulClaimIsTerminalNormalError() {
        db.failUpdatesContaining("hex_bazaar_orders");   // zapis zlecenia pada -> zwrot; claim się zapisze
        TestSeam seam = new TestSeam();
        seam.mode = TestSeam.Mode.REJECT;                // planowanie odrzucone ZANIM runnable ruszył
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.DIRECT, seam);
        PlayerMock p = seller(5);

        PlaceOutcome o = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10")).join();

        assertEquals(PlaceResult.DB_FAILED, o.result(), "CLAIMED -> zwykły błąd (fachowy), nie krytyczny");
        assertEquals(0, countDiamonds(p), "brak mutacji ekwipunku (przedmioty poszły w claim, nie z powrotem)");
        assertEquals(1, claimInsertCount(db), "dokładnie jeden claim");
        assertGuardReleased(svc, p);
    }

    // ---------------------------------------------------------------- 2) odrzucenie planowania + claim FAIL

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void schedulerRejectionWithFailedClaimIsCompensationFailed() {
        db.failUpdatesContaining("INSERT INTO");   // pada i zapis zlecenia, i claim
        TestSeam seam = new TestSeam();
        seam.mode = TestSeam.Mode.REJECT;
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.DIRECT, seam);
        PlayerMock p = seller(5);

        var future = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        PlaceOutcome o = future.join();

        assertTrue(future.isDone(), "future terminalny mimo odrzucenia planowania i nieudanego claim");
        assertEquals(PlaceResult.COMPENSATION_FAILED, o.result());
        assertGuardReleased(svc, p);
    }

    // ---------------------------------------------------------------- 3) przyjęte, ale niewykonane + disable

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void acceptedButNotRunThenDisableRecoversExactlyOnce() {
        db.failUpdatesContaining("hex_bazaar_orders");
        TestSeam seam = new TestSeam();
        seam.mode = TestSeam.Mode.QUEUE;               // zadanie przyjęte, ale NIE wykonane
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.DIRECT, seam);
        PlayerMock p = seller(5);

        var future = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        assertFalse(future.isDone(), "zwrot zakolejkowany, jeszcze nie wykonany");
        assertEquals(1, seam.queued.size());

        // Disable-drain na wątku głównym: przejmuje nierozpoczętą operację.
        svc.drainPendingReturnsOnDisable(2000L);

        assertTrue(future.isDone(), "drain domknął recovery (bez hängender Future)");
        assertEquals(PlaceResult.DB_FAILED, future.join().result());
        assertEquals(5, countDiamonds(p), "przedmioty wróciły do ekwipunku (dokładnie raz)");

        // Zakolejkowany, spóźniony runnable NIE dubluje.
        seam.runQueued();
        assertEquals(5, countDiamonds(p), "brak podwójnego zwrotu po uruchomieniu spóźnionego runnable");
        assertEquals(0, claimInsertCount(db), "zwrot do ekwipunku -> bez claim");
        assertGuardReleased(svc, p);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void disableTimeoutReportsEveryUnresolvedRecoveryWithoutSecondReturn() {
        db.failUpdatesContaining("hex_bazaar_orders");
        TestSeam seam = new TestSeam();
        seam.mode = TestSeam.Mode.QUEUE;
        seam.forcedResult = InventoryFit.Result.NOT_FIT_REVERTED;
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.DIRECT, seam);
        PlayerMock p = seller(5);

        var future = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        assertFalse(future.isDone(), "zwrot czeka w kolejce przed drain-em");
        assertEquals(1, seam.queued.size());

        // Claim uruchomiony przez drain nigdy się nie kończy: get(0 ms) musi wrócić natychmiast,
        // pozostawić dokładnie jedną operację RUNNING i zapisać identyfikowalny SEVERE.
        dbService.setMode(TestDatabaseService.Mode.PENDING_ASYNC);
        List<String> severeMessages = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record != null && record.getLevel().intValue() >= Level.SEVERE.intValue()) {
                    severeMessages.add(record.getMessage());
                }
            }

            @Override public void flush() { }
            @Override public void close() { }
        };
        plugin.getLogger().addHandler(capture);
        try {
            int unresolved = svc.drainPendingReturnsOnDisable(0L);

            assertEquals(1, unresolved, "dokładnie jedna niedomknięta operacja po timeout-cie");
            assertFalse(future.isDone(), "nie udajemy terminalnego wyniku, gdy claim nadal może się zatwierdzić");
            assertEquals(0, countDiamonds(p), "brak drugiego zwrotu do ekwipunku po rozpoczęciu claim-u");
            assertEquals(0, claimInsertCount(db), "wiszący async nie wykonał jeszcze zapisu claim-u");

            String diagnostic = String.join("\n", severeMessages);
            assertTrue(diagnostic.contains("odzysk="), "log zawiera stabilny identyfikator odzysku");
            assertTrue(diagnostic.contains("gracz=" + p.getUniqueId()), "log zawiera UUID gracza");
            assertTrue(diagnostic.contains("stan=RUNNING"), "log zawiera stan operacji");
            assertTrue(diagnostic.contains("stacki=1"), "log zawiera liczbę stacków bez danych NBT");

            // Spóźniony zaplanowany runnable przegrywa CAS i nie uruchamia zwrotu drugi raz.
            seam.runQueued();
            assertEquals(0, countDiamonds(p));
            assertEquals(PlaceResult.BUSY,
                    svc.placeSellOffer(p, "diamond", 100_000, new BigDecimal("10")).join().result(),
                    "guard pozostaje zajęty, dopóki rzeczywisty claim jest w locie");
        } finally {
            plugin.getLogger().removeHandler(capture);
        }
    }

    // ---------------------------------------------------------------- 4) wyścig runnable vs disable

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void raceDisableFirstThenRunnableReturnsExactlyOnce() {
        db.failUpdatesContaining("hex_bazaar_orders");
        TestSeam seam = new TestSeam();
        seam.mode = TestSeam.Mode.QUEUE;
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.DIRECT, seam);
        PlayerMock p = seller(5);

        var future = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        svc.drainPendingReturnsOnDisable(2000L);   // drain wygrywa CAS
        seam.runQueued();                          // spóźniony runnable -> no-op

        assertTrue(future.isDone());
        assertEquals(5, countDiamonds(p), "dokładnie jeden zwrot do ekwipunku");
        assertEquals(0, claimInsertCount(db), "nigdy ekwipunek I claim jednocześnie");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void raceRunnableFirstThenDisableReturnsExactlyOnce() {
        db.failUpdatesContaining("hex_bazaar_orders");
        TestSeam seam = new TestSeam();
        seam.mode = TestSeam.Mode.QUEUE;
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.DIRECT, seam);
        PlayerMock p = seller(5);

        var future = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));
        seam.runQueued();                          // runnable wygrywa CAS -> ADDED_FULLY
        svc.drainPendingReturnsOnDisable(2000L);   // drain: CAS nie przechodzi, tylko czeka (out już gotowy)

        assertTrue(future.isDone());
        assertEquals(5, countDiamonds(p), "dokładnie jeden zwrot do ekwipunku");
        assertEquals(0, claimInsertCount(db), "nigdy ekwipunek I claim jednocześnie");
    }

    // ---------------------------------------------------------------- 5) ADDED_FULLY -> bez claim

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void addedFullyDoesNotCreateClaim() {
        db.failUpdatesContaining("hex_bazaar_orders");
        TestSeam seam = new TestSeam();   // RUN, realny InventoryFit
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.DIRECT, seam);
        PlayerMock p = seller(5);

        PlaceOutcome o = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10")).join();

        assertEquals(PlaceResult.DB_FAILED, o.result());
        assertEquals(5, countDiamonds(p), "wszystko wróciło do ekwipunku");
        assertEquals(0, claimInsertCount(db), "ADDED_FULLY -> żadnego claim");
        assertGuardReleased(svc, p);
    }

    // ---------------------------------------------------------------- 6) NOT_FIT_REVERTED -> jeden atomowy claim

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void offlineReturnCreatesExactlyOneAtomicClaimBatch() {
        db.failUpdatesContaining("hex_bazaar_orders");
        TestSeam seam = new TestSeam();
        seam.mode = TestSeam.Mode.QUEUE;
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.DIRECT, seam);
        PlayerMock p = seller(5);

        var future = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10"));   // zdjęcie online
        p.disconnect();          // offline zanim zwrot się wykona
        seam.runQueued();        // doReturnOnMain: offline -> NOT_FIT_REVERTED -> atomowy claim

        assertEquals(PlaceResult.DB_FAILED, future.join().result(), "CLAIMED -> zwykły błąd");
        assertEquals(1, claimInsertCount(db), "dokładnie jeden atomowy claim (jedna transakcja)");
    }

    // ---------------------------------------------------------------- 7) STATE_UNCERTAIN (realnie rozpoczęte)

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void stateUncertainAfterBegunInventoryAttemptCreatesNoClaim() {
        db.failUpdatesContaining("hex_bazaar_orders");
        TestSeam seam = new TestSeam();
        seam.forcedResult = InventoryFit.Result.STATE_UNCERTAIN;   // próba ekwipunku realnie zaczęta, nierozstrzygalna
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.DIRECT, seam);
        PlayerMock p = seller(5);

        PlaceOutcome o = svc.placeSellOffer(p, "diamond", 5, new BigDecimal("10")).join();

        assertEquals(PlaceResult.COMPENSATION_FAILED, o.result());
        assertEquals(0, claimInsertCount(db), "STATE_UNCERTAIN nigdy nie tworzy claim (ryzyko duplikacji)");
        assertGuardReleased(svc, p);
    }

    // ---------------------------------------------------------------- 8) claimAllInOneTx: totalność

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void claimAllInOneTxTotalOnSyncThrow() {
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.THROW_ON_ASYNC, new TestSeam());
        var f = svc.claimAllInOneTx(UUID.randomUUID(), List.of(new ItemStack(Material.DIAMOND, 5)));
        assertTrue(f.isDone(), "synchroniczny wyjątek async -> terminalny future (bez hängen)");
        assertFalse(f.join());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void claimAllInOneTxTotalOnNullFuture() {
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.NULL_ASYNC, new TestSeam());
        var f = svc.claimAllInOneTx(UUID.randomUUID(), List.of(new ItemStack(Material.DIAMOND, 5)));
        assertTrue(f.isDone(), "null future async -> terminalny false");
        assertFalse(f.join());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void claimAllInOneTxTotalOnExceptionalCompletion() {
        db.failUpdatesContaining("INSERT INTO");   // tx rzuca -> exceptional future
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.DIRECT, new TestSeam());
        var f = svc.claimAllInOneTx(UUID.randomUUID(), List.of(new ItemStack(Material.DIAMOND, 5)));
        assertTrue(f.isDone());
        assertFalse(f.join(), "wyjątkowe domknięcie -> terminalny false");
    }

    // ---------------------------------------------------------------- meta zachowane w claim

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void claimPreservesFullItemMeta() {
        BazaarOrderService svc = orderService(TestDatabaseService.Mode.DIRECT, new TestSeam());
        ItemStack decorated = decoratedDiamond();

        assertTrue(svc.claimAllInOneTx(UUID.randomUUID(), List.of(decorated.clone())).join());

        byte[] blob = firstClaimBlob(db);
        assertNotNull(blob, "claim zapisany z blobem przedmiotu");
        ItemStack restored = ItemSerializer.deserialize(blob);
        assertTrue(restored.isSimilar(decorated),
                "NBT/PDC/nazwa/lore/enchanty/CustomModelData zachowane w claim");
    }

    private static byte[] firstClaimBlob(InMemoryDb db) {
        for (InMemoryDb.Op op : db.operations()) {
            if (op.sql().contains("INSERT INTO") && op.sql().contains("hex_auction_claims")) {
                return (byte[]) op.params().get(1);   // (owner, item_blob, ...) - blob to indeks 1
            }
        }
        return null;
    }

    private static ItemStack decoratedDiamond() {
        NamespacedKey key = new NamespacedKey("hexauctionbazaar", "recovery-test");
        ItemStack item = new ItemStack(Material.DIAMOND, 3);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Zaklęty Diament"));
        meta.lore(List.of(Component.text("Blask")));
        meta.addEnchant(Enchantment.UNBREAKING, 2, true);
        meta.setCustomModelData(11);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "pdc-val");
        item.setItemMeta(meta);
        return item;
    }
}
