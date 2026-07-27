package hexnpc.shop.audit;

import hexnpc.integration.HexCoreBridge;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Cienki, reflektywny wrapper na {@code hex.core.api.db.DatabaseService} oraz
 * {@code Db} z HexCore. Cała refleksja jest tu odizolowana, dzięki czemu
 * reszta HexNPC nie dotyka typów {@code hex.core.*}.
 *
 * <p>Metody rozwiązywane są z interfejsów HexCore (a nie z klas
 * implementacyjnych), więc pakietowo-prywatne implementacje nie powodują
 * problemów z dostępnością. HexNPC nie tworzy własnej puli ani połączenia —
 * korzysta wyłącznie z zasobów HexCore i nigdy nie wywołuje
 * {@code shutdown()}.
 */
public final class HexCoreDatabase implements AuditDatabase {

    private final Object databaseService;
    private final Object db;
    private final Method asyncRun;   // DatabaseService.asyncRun(Runnable) -> CompletableFuture<Void>
    private final Method update;     // Db.update(String, Object...) -> int
    private final Method tableName;  // Db.t(String) -> String
    private final Method tablePrefix; // Db.tablePrefix() -> String

    private HexCoreDatabase(Object databaseService, Object db, Method asyncRun,
                            Method update, Method tableName, Method tablePrefix) {
        this.databaseService = databaseService;
        this.db = db;
        this.asyncRun = asyncRun;
        this.update = update;
        this.tableName = tableName;
        this.tablePrefix = tablePrefix;
    }

    /** Rozwiązuje handle DB z HexCore. Pusty, gdy HexCore/DB niedostępne. */
    public static Optional<AuditDatabase> resolve(HexCoreBridge bridge, Logger logger) {
        if (bridge == null) {
            return Optional.empty();
        }
        Optional<Object> dsOpt = bridge.databaseService();
        if (dsOpt.isEmpty()) {
            return Optional.empty();
        }
        Object ds = dsOpt.get();
        try {
            ClassLoader cl = ds.getClass().getClassLoader();
            Class<?> dsIface = Class.forName("hex.core.api.db.DatabaseService", false, cl);
            Class<?> dbIface = Class.forName("hex.core.api.db.Db", false, cl);
            Method dbGetter = dsIface.getMethod("db");
            Method asyncRun = dsIface.getMethod("asyncRun", Runnable.class);
            Method update = dbIface.getMethod("update", String.class, Object[].class);
            Method tableName = dbIface.getMethod("t", String.class);
            Method tablePrefix = dbIface.getMethod("tablePrefix");
            Object db = dbGetter.invoke(ds);
            if (db == null) {
                return Optional.empty();
            }
            return Optional.of(new HexCoreDatabase(ds, db, asyncRun, update, tableName, tablePrefix));
        } catch (Throwable t) {
            if (logger != null) {
                logger.fine("HexNPC: HexCore DatabaseService/Db reflection failed: " + t.getMessage());
            }
            return Optional.empty();
        }
    }

    /** Pełna nazwa tabeli z prefiksem HexCore ({@code Db.t(name)}). */
    @Override
    public String table(String name) {
        try {
            Object out = tableName.invoke(db, name);
            return out == null ? name : out.toString();
        } catch (Throwable t) {
            // Fallback: prefiks + nazwa.
            try {
                Object prefix = tablePrefix.invoke(db);
                return (prefix == null ? "" : prefix.toString()) + name;
            } catch (Throwable ignored) {
                return name;
            }
        }
    }

    /**
     * Uruchamia pracę DB na executorze HexCore (poza wątkiem głównym) i zwraca
     * future zakończenia. Wyjątki wewnątrz {@code work} są propagowane do
     * zwróconego future (nie do wątku głównego).
     */
    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<Void> submit(Runnable work) {
        try {
            Object future = asyncRun.invoke(databaseService, (Runnable) work);
            if (future instanceof CompletableFuture<?> cf) {
                return (CompletableFuture<Void>) cf;
            }
            return CompletableFuture.completedFuture(null);
        } catch (Throwable t) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(t);
            return failed;
        }
    }

    /**
     * Wykonuje parametryzowany UPDATE/DDL przez {@code Db.update}. Wywoływać
     * WYŁĄCZNIE wewnątrz {@link #submit(Runnable)} (na wątku DB HexCore).
     */
    @Override
    public int execUpdate(String sql, Object... params) throws Exception {
        return (int) update.invoke(db, sql, params);
    }
}
