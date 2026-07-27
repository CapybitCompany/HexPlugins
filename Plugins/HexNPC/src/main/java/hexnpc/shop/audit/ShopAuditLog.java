package hexnpc.shop.audit;

import hexnpc.shop.config.ShopConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Append-only audyt transakcji sklepu, zapisywany do MySQL wyłącznie przez
 * współdzieloną bazę HexCore. Kluczowe gwarancje:
 *
 * <ul>
 *   <li>Brak własnej puli/połączenia; nigdy nie zamyka puli HexCore.</li>
 *   <li>Wszystkie DDL/INSERT idą przez async-executor HexCore — nigdy na wątku
 *       głównym.</li>
 *   <li>Schemat tworzony jest raz ({@code CREATE TABLE IF NOT EXISTS}); INSERT-y
 *       są łańcuchowane po zakończeniu tworzenia schematu.</li>
 *   <li>Brak HexCore/bazy lub błąd audytu nigdy nie blokuje ani nie cofa
 *       transakcji sklepu; błędy są logowane po polsku z ograniczeniem
 *       częstotliwości.</li>
 * </ul>
 */
public final class ShopAuditLog {

    private static final long LOG_INTERVAL_MS = 60_000L;

    private final Supplier<ShopConfig> config;
    private final Logger logger;
    private final Supplier<Optional<AuditDatabase>> databaseResolver;

    private volatile boolean active;
    private volatile Generation generation;
    private final AtomicLong lastLog = new AtomicLong(0L);
    private final List<CompletableFuture<Void>> pending = new CopyOnWriteArrayList<>();
    private boolean loggedUnavailable = false;

    public ShopAuditLog(Supplier<ShopConfig> config, Logger logger,
                        Supplier<Optional<AuditDatabase>> databaseResolver) {
        this.config = config;
        this.logger = logger;
        this.databaseResolver = databaseResolver;
    }

    public boolean isActive() {
        return active;
    }

    public String tableName() {
        Generation gen = generation;
        return gen == null ? null : gen.table;
    }

    /** Ile operacji audytu jest w toku (schema + INSERT-y). Do testów/diagnostyki. */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * Migawka jednej „generacji" audytu (baza + tabela + schema-future + gotowy
     * INSERT). Snapshotowana atomowo, więc reload z nową tabelą nigdy nie miesza
     * starej schema-future z nową nazwą tabeli.
     */
    private record Generation(AuditDatabase db, String table,
                              CompletableFuture<Void> schema, String insertSql) {
    }

    /**
     * (Re)inicjalizuje audyt: rozwiązuje bazę HexCore, wyznacza nazwę tabeli z
     * prefiksem i asynchronicznie tworzy schemat. Bezpieczne do wielokrotnego
     * wywołania (reload). Nie blokuje wątku głównego.
     */
    public synchronized void init() {
        ShopConfig cfg = config == null ? null : config.get();
        ShopConfig.AuditLog auditCfg = cfg == null ? null : cfg.auditLog();
        if (auditCfg == null || !auditCfg.enabled()) {
            active = false;
            generation = null;
            return;
        }
        Optional<AuditDatabase> resolved = databaseResolver == null
                ? Optional.empty() : databaseResolver.get();
        if (resolved == null || resolved.isEmpty()) {
            active = false;
            generation = null;
            if (!loggedUnavailable && logger != null) {
                loggedUnavailable = true;
                logger.info("HexNPC: audyt transakcji nieaktywny — HexCore lub baza danych są niedostępne.");
            }
            return;
        }
        loggedUnavailable = false;
        AuditDatabase database = resolved.get();
        String tableName = database.table(auditCfg.table());
        final String ddl = createTableSql(tableName);
        // Tworzenie schematu jest śledzone w pending, aby shutdown mógł na nie
        // poczekać nawet przy zerowej liczbie INSERT-ów.
        CompletableFuture<Void> schema = database.submit(() -> {
            try {
                database.execUpdate(ddl);
            } catch (Throwable t) {
                rateLimitedWarn("nie udało się utworzyć tabeli audytu", t);
                throw new RuntimeException(t);
            }
        });
        trackUntilDone(schema);
        this.generation = new Generation(database, tableName, schema, insertSql(tableName));
        this.active = true;
    }

    /**
     * Zapisuje wpis audytu (fire-and-forget). Nigdy nie rzuca, nie blokuje i
     * nie wpływa na wynik transakcji. INSERT startuje dopiero po zakończeniu
     * tworzenia schematu.
     */
    public void record(AuditEntry entry) {
        Generation gen = generation;
        if (!active || gen == null || entry == null) {
            return;
        }
        final Object[] params;
        try {
            params = entry.toParams();
        } catch (Throwable t) {
            return;
        }
        final AuditDatabase database = gen.db;
        final String sql = gen.insertSql;
        // Pełny łańcuch (czekaj na schema -> INSERT) jest OD RAZU śledzony w
        // pending, więc shutdown zawsze go obejmie. thenCompose zamiast luźnego
        // whenComplete, które startowałoby nową pracę poza pending.
        CompletableFuture<Void> chain = gen.schema.thenCompose(v -> {
            if (!active) {
                // Po shutdown nie startujemy nowego INSERT-u.
                return CompletableFuture.completedFuture(null);
            }
            return database.submit(() -> {
                try {
                    database.execUpdate(sql, params);
                } catch (Throwable t) {
                    rateLimitedWarn("nie udało się zapisać wpisu audytu", t);
                }
            });
        }).exceptionally(err -> {
            rateLimitedWarn("schemat audytu niedostępny — pomijam wpis", err);
            return null;
        });
        trackUntilDone(chain);
    }

    private void trackUntilDone(CompletableFuture<Void> future) {
        pending.add(future);
        future.whenComplete((v, e) -> pending.remove(future));
    }

    /**
     * Wywoływane przy disable. NIE zamyka puli HexCore — czeka jedynie krótko
     * na własne, będące w toku zapisy audytu.
     */
    public void shutdown() {
        shutdown(2000L);
    }

    /** Wariant z konfigurowalnym limitem oczekiwania (do testów). */
    void shutdown(long timeoutMillis) {
        active = false;
        List<CompletableFuture<Void>> snapshot = new ArrayList<>(pending);
        if (snapshot.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(snapshot.toArray(new CompletableFuture[0]))
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // Best-effort — nie blokujemy wyłączania serwera na audyt.
        }
    }

    private void rateLimitedWarn(String message, Throwable t) {
        if (logger == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastLog.get();
        if (now - last >= LOG_INTERVAL_MS && lastLog.compareAndSet(last, now)) {
            String detail = t == null ? "" : (" (" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + ")");
            logger.warning("HexNPC: audyt transakcji — " + message + detail);
        }
    }

    private static String createTableSql(String table) {
        return "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "transaction_id CHAR(36) NOT NULL,"
                + "created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),"
                + "player_uuid CHAR(36) NOT NULL,"
                + "player_name VARCHAR(16) NOT NULL,"
                + "shop_id VARCHAR(128) NOT NULL,"
                + "item_id VARCHAR(128) NOT NULL,"
                + "material VARCHAR(64) NOT NULL,"
                + "action VARCHAR(16) NOT NULL,"
                + "requested_quantity INT NOT NULL,"
                + "actual_quantity INT NOT NULL,"
                + "total_price DECIMAL(20,8) NOT NULL,"
                + "balance_after DECIMAL(20,8) NULL,"
                + "status VARCHAR(24) NOT NULL,"
                + "reason VARCHAR(255) NULL,"
                + "KEY idx_created (created_at),"
                + "KEY idx_player_created (player_uuid, created_at),"
                + "KEY idx_shop_item_created (shop_id, item_id, created_at),"
                + "KEY idx_txid (transaction_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    private static String insertSql(String table) {
        return "INSERT INTO " + table + " ("
                + "transaction_id, player_uuid, player_name, shop_id, item_id, material, action,"
                + "requested_quantity, actual_quantity, total_price, balance_after, status, reason"
                + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
    }
}
