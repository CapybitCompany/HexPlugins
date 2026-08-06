package hex.auctionbazaar.audit.service;

import hex.auctionbazaar.audit.model.AuditAction;
import hex.auctionbazaar.audit.repository.AuditLogRepository;
import hex.auctionbazaar.bridge.HexCoreBridge;
import hex.auctionbazaar.config.MessagesConfig;
import hex.auctionbazaar.testutil.InMemoryDb;
import hex.auctionbazaar.testutil.TestDatabaseService;
import hex.auctionbazaar.testutil.TestHexApi;
import hex.auctionbazaar.util.MessageFactory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #7: {@link AuditService#log} rejestruje śledzony future w {@code pending} PRZED startem pracy
 * async i pod tym samym lockiem co migawka {@link AuditService#awaitPending}. Dzięki temu
 * {@code awaitPending(stopAcceptingNew=true)} nie gubi wpisu zaakceptowanego tuż przed startem async.
 * Dodatkowo synchroniczny błąd zgłoszenia async kończy future terminalnie i nie leci do wołającego.
 */
class AuditShutdownRaceTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private AuditService audit(HexCoreBridge hexCore, InMemoryDb db) {
        Logger log = Logger.getAnonymousLogger();
        MessageFactory messages = new MessageFactory(() -> new MessagesConfig(java.util.Map.of()), () -> "");
        return new AuditService(log, hexCore, new AuditLogRepository(db), messages);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void logRegistersPendingBeforeAsyncStartSoAwaitPendingSeesIt() throws Exception {
        InMemoryDb db = new InMemoryDb();
        HexCoreBridge hexCore = TestHexApi.bootstrap(plugin, new TestDatabaseService(db));
        AuditService audit = audit(hexCore, db);

        CountDownLatch accepted = new CountDownLatch(1);   // sygnał: wpis zarejestrowany w pending
        CountDownLatch release = new CountDownLatch(1);    // pozwolenie na start pracy async
        // Bariera odpala się MIĘDZY akceptacją (rejestracją w pending) a startem async.
        audit.asyncSubmitBarrier = () -> {
            accepted.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Thread logger = new Thread(() -> audit.log(audit.builder()
                .action(AuditAction.ADMIN_ACTION).market(AuditAction.MARKET_ADMIN)
                .result(AuditAction.RESULT_OK)), "audit-log");
        logger.start();

        accepted.await();   // wpis zaakceptowany; praca async jeszcze NIE wystartowała
        // Rejestracja poprzedza start async: future jest już śledzone.
        assertEquals(1, audit.pendingCount(), "future zarejestrowane przed startem async");
        assertTrue(db.operations().isEmpty(), "insert jeszcze nie wystartował (pauza przed async)");

        // Równolegle: zamykanie ze stop-accepting. Migawka MUSI zawierać zaakceptowany wpis.
        Thread closer = new Thread(() -> audit.awaitPending(5000L, true), "audit-close");
        closer.start();

        release.countDown();   // teraz praca async rusza i domyka future
        closer.join();
        logger.join();

        assertTrue(audit.isShutdown(), "po awaitPending(stopAcceptingNew=true) nie przyjmujemy nowych");
        assertEquals(0, audit.pendingCount(), "pending domknięte i usunięte dokładnie raz");
        boolean insertRecorded = db.operations().stream()
                .anyMatch(op -> op.sql().contains("INSERT INTO") && op.sql().contains("audit_log"));
        assertTrue(insertRecorded, "awaitPending doczekał zaakceptowanego insertu (nie zgubił go)");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void synchronousAsyncSubmitFailureIsTerminalNotPropagated() {
        InMemoryDb db = new InMemoryDb();
        TestDatabaseService dbService = new TestDatabaseService(db, TestDatabaseService.Mode.THROW_ON_ASYNC);
        HexCoreBridge hexCore = TestHexApi.bootstrap(plugin, dbService);
        AuditService audit = audit(hexCore, db);

        // hexCore.async() rzuca synchronicznie -> log() NIE może rzucić do wołającego.
        Long id = audit.log(audit.builder()
                .action(AuditAction.ADMIN_ACTION).market(AuditAction.MARKET_ADMIN)
                .result(AuditAction.RESULT_OK)).join();

        assertEquals(-1L, id, "terminalny wynik -1 przy synchronicznym błędzie async");
        assertEquals(0, audit.pendingCount(), "future domknięte i usunięte z pending");
        assertFalse(audit.isShutdown(), "pojedynczy błąd async nie oznacza trwałego stop-accepting");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void afterStopAcceptingNewLogDoesNotStartInsert() {
        InMemoryDb db = new InMemoryDb();
        HexCoreBridge hexCore = TestHexApi.bootstrap(plugin, new TestDatabaseService(db));
        AuditService audit = audit(hexCore, db);

        audit.awaitPending(0L, true);   // stop-accepting
        db.clearOps();
        Long id = audit.log(audit.builder()
                .action(AuditAction.ADMIN_ACTION).market(AuditAction.MARKET_ADMIN)
                .result(AuditAction.RESULT_OK)).join();

        assertEquals(-1L, id, "po stop-accepting log nic nie wstawia");
        assertTrue(db.operations().isEmpty(), "żadnego insertu po stop-accepting");
        assertEquals(0, audit.pendingCount());
    }
}
