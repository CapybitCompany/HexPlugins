package hexnpc.shop.audit;

import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.config.ShopMessages;
import hexnpc.shop.model.ShopLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audyt: nazwa tabeli z prefiksem, schemat przed INSERT, zapytania
 * parametryzowane, poprawne action/status/ilości/cena oraz odporność na
 * awarię bazy. Wszystkie zapisy idą wyłącznie przez {@code submit()}.
 */
class ShopAuditLogTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    /** Atrapa bazy: uruchamia pracę synchronicznie i rejestruje SQL/parametry. */
    private static final class FakeDatabase implements AuditDatabase {
        final List<String> sql = new ArrayList<>();
        final List<Object[]> params = new ArrayList<>();
        boolean insideSubmit = false;
        boolean execOutsideSubmit = false;
        boolean failInsert = false;
        boolean failSchema = false;

        @Override
        public String table(String name) {
            return "hex_" + name;
        }

        @Override
        public CompletableFuture<Void> submit(Runnable work) {
            insideSubmit = true;
            try {
                work.run();
                return CompletableFuture.completedFuture(null);
            } catch (Throwable t) {
                CompletableFuture<Void> f = new CompletableFuture<>();
                f.completeExceptionally(t);
                return f;
            } finally {
                insideSubmit = false;
            }
        }

        @Override
        public int execUpdate(String s, Object... p) {
            if (!insideSubmit) {
                execOutsideSubmit = true;
            }
            sql.add(s);
            params.add(p);
            if (failSchema && s.startsWith("CREATE")) {
                throw new RuntimeException("schema down");
            }
            if (failInsert && s.startsWith("INSERT")) {
                throw new RuntimeException("insert down");
            }
            return 1;
        }
    }

    private ShopAuditLog audit(FakeDatabase db) {
        return new ShopAuditLog(ShopConfig::defaults, Logger.getLogger("audit-test"),
                () -> Optional.of(db));
    }

    private AuditEntry sample(AuditAction action, AuditStatus status, int reqQty, int actQty) {
        return new AuditEntry(UUID.randomUUID().toString(), UUID.randomUUID(), "Tester",
                "starter", "diamond", "DIAMOND", action, reqQty, actQty,
                new BigDecimal("500.00"), new BigDecimal("1234.50"), status, null);
    }

    @Test
    void schemaCreatedBeforeInsertWithPrefixedTable() {
        FakeDatabase db = new FakeDatabase();
        ShopAuditLog log = audit(db);
        log.init();
        assertTrue(log.isActive());
        assertEquals("hex_npc_shop_audit", log.tableName(), "tabela musi mieć prefiks HexCore");
        assertEquals(1, db.sql.size());
        assertTrue(db.sql.get(0).startsWith("CREATE TABLE IF NOT EXISTS hex_npc_shop_audit"));

        log.record(sample(AuditAction.BUY, AuditStatus.SUCCESS, 64, 64));
        assertEquals(2, db.sql.size(), "INSERT po utworzeniu schematu");
        assertTrue(db.sql.get(1).startsWith("INSERT INTO hex_npc_shop_audit"));
    }

    @Test
    void insertIsParameterizedWithCorrectValues() {
        FakeDatabase db = new FakeDatabase();
        ShopAuditLog log = audit(db);
        log.init();
        AuditEntry entry = sample(AuditAction.SELL_ALL, AuditStatus.SUCCESS, 100, 100);
        log.record(entry);

        String insert = db.sql.get(1);
        assertTrue(insert.contains("VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"), "zapytanie musi być parametryzowane");
        Object[] p = db.params.get(1);
        assertEquals(13, p.length);
        assertEquals("SELL_ALL", p[6], "action");
        assertEquals(100, p[7], "requested_quantity");
        assertEquals(100, p[8], "actual_quantity");
        assertEquals(new BigDecimal("500.00"), p[9], "total_price");
        assertEquals(new BigDecimal("1234.50"), p[10], "balance_after");
        assertEquals("SUCCESS", p[11], "status");
    }

    @Test
    void execNeverRunsOutsideSubmit() {
        FakeDatabase db = new FakeDatabase();
        ShopAuditLog log = audit(db);
        log.init();
        log.record(sample(AuditAction.BUY, AuditStatus.SUCCESS, 64, 64));
        assertFalse(db.execOutsideSubmit, "Db.update nigdy poza submit() (nigdy na wątku głównym w produkcji)");
    }

    @Test
    void insertFailureDoesNotThrow() {
        FakeDatabase db = new FakeDatabase();
        db.failInsert = true;
        ShopAuditLog log = audit(db);
        log.init();
        // Nie może rzucić — audyt jest fire-and-forget.
        log.record(sample(AuditAction.BUY, AuditStatus.SUCCESS, 64, 64));
        assertEquals(2, db.sql.size(), "INSERT był próbowany mimo awarii");
    }

    @Test
    void schemaFailureSkipsInsertWithoutThrowing() {
        FakeDatabase db = new FakeDatabase();
        db.failSchema = true;
        ShopAuditLog log = audit(db);
        log.init();
        log.record(sample(AuditAction.BUY, AuditStatus.SUCCESS, 64, 64));
        // Schemat padł -> INSERT pominięty, brak wyjątku.
        assertEquals(1, db.sql.size());
        assertTrue(db.sql.get(0).startsWith("CREATE"));
    }

    @Test
    void unavailableDatabaseDisablesAuditGracefully() {
        ShopAuditLog log = new ShopAuditLog(ShopConfig::defaults, Logger.getLogger("audit-test"),
                Optional::empty);
        log.init();
        assertFalse(log.isActive());
        // record nie może rzucić przy nieaktywnym audycie.
        log.record(sample(AuditAction.BUY, AuditStatus.SUCCESS, 1, 1));
        assertNotNull(log);
    }

    // ===== Sterowalna baza: kontrola momentu wykonania (schema/INSERT) =====

    /** Baza, w której {@code submit} kolejkuje pracę; test decyduje, kiedy się wykona. */
    private static final class ControllableDatabase implements AuditDatabase {
        final List<String> sql = new ArrayList<>();
        final List<Runnable> queue = new ArrayList<>();
        final List<CompletableFuture<Void>> futures = new ArrayList<>();
        boolean insideSubmit = false;

        @Override
        public String table(String name) {
            return "hex_" + name;
        }

        @Override
        public CompletableFuture<Void> submit(Runnable work) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            queue.add(work);
            futures.add(f);
            return f;
        }

        @Override
        public int execUpdate(String s, Object... p) {
            sql.add(s);
            return 1;
        }

        /** Uruchamia całą zakolejkowaną pracę (w tym pracę dołożoną w trakcie). */
        void flushAll() {
            while (!queue.isEmpty()) {
                Runnable r = queue.remove(0);
                CompletableFuture<Void> f = futures.remove(0);
                insideSubmit = true;
                try {
                    r.run();
                    f.complete(null);
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                } finally {
                    insideSubmit = false;
                }
            }
        }

        long insertCount() {
            return sql.stream().filter(s -> s.startsWith("INSERT")).count();
        }
    }

    private Supplier<ShopConfig> cfgWithTable(AtomicReference<String> table) {
        return () -> new ShopConfig(true, true, "&8x", true, ShopLayout.defaults(54),
                List.of(1, 64), true, true, true, 30, 4, 2,
                ShopConfig.Confirmation.defaults(),
                new ShopConfig.AuditLog(true, table.get(), true), ShopMessages.defaults());
    }

    @Test
    void recordWaitingOnSchemaIsImmediatelyTrackedAsPending() {
        ControllableDatabase db = new ControllableDatabase();
        ShopAuditLog log = new ShopAuditLog(ShopConfig::defaults, Logger.getLogger("audit-test"),
                () -> Optional.of(db));
        log.init(); // schema zakolejkowane, jeszcze niewykonane
        assertEquals(1, log.pendingCount(), "sama inicjalizacja schematu jest śledzona");
        log.record(sample(AuditAction.BUY, AuditStatus.SUCCESS, 100, 100));
        // Cały łańcuch (czekaj na schema -> INSERT) jest w pending OD RAZU.
        assertEquals(2, log.pendingCount(), "record czekający na schemat jest natychmiast w pending");
    }

    @Test
    void shutdownBeforeSchemaCompletionSkipsInsert() {
        ControllableDatabase db = new ControllableDatabase();
        ShopAuditLog log = new ShopAuditLog(ShopConfig::defaults, Logger.getLogger("audit-test"),
                () -> Optional.of(db));
        log.init();
        log.record(sample(AuditAction.BUY, AuditStatus.SUCCESS, 100, 100));
        log.shutdown(50L); // active=false; schema jeszcze niegotowe
        db.flushAll();       // schemat kończy się PO shutdown
        assertEquals(0, db.insertCount(), "po shutdown żaden INSERT nie może wystartować");
        assertTrue(db.sql.stream().anyMatch(s -> s.startsWith("CREATE")));
    }

    @Test
    void oldGenerationRecordWritesToOldTableAfterReload() {
        ControllableDatabase db = new ControllableDatabase();
        AtomicReference<String> table = new AtomicReference<>("audit_a");
        ShopAuditLog log = new ShopAuditLog(cfgWithTable(table), Logger.getLogger("audit-test"),
                () -> Optional.of(db));
        log.init(); // generacja 1 -> hex_audit_a
        log.record(sample(AuditAction.BUY, AuditStatus.SUCCESS, 100, 100)); // łańcuch gen1
        // Reload ze zmienioną nazwą tabeli.
        table.set("audit_b");
        log.init(); // generacja 2 -> hex_audit_b
        db.flushAll();
        // Rekord z generacji 1 pisze do STAREJ tabeli, nie do nowej.
        assertTrue(db.sql.stream().anyMatch(s -> s.startsWith("INSERT INTO hex_audit_a")),
                "stary rekord trafia do starej tabeli");
        assertFalse(db.sql.stream().anyMatch(s -> s.startsWith("INSERT INTO hex_audit_b")),
                "stary rekord nie może trafić do nowej tabeli");
    }
}
