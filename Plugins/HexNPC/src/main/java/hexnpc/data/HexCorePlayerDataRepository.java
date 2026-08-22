package hexnpc.data;

import hexnpc.integration.HexCoreBridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Persistent HexNPC player-data store backed by the shared HexCore DB/executor.
 * No JDBC pool is owned or closed by HexNPC.
 */
public final class HexCorePlayerDataRepository implements PlayerDataRepository {
    private static final String TABLE = "hexnpc_player_data";

    private final Object databaseService;
    private final Object db;
    private final Method async;
    private final Method update;
    private final Method query;
    private final Method tableName;
    private final Class<?> rowMapperClass;
    private final Logger logger;

    private HexCorePlayerDataRepository(Object databaseService, Object db, Method async,
                                        Method update, Method query, Method tableName,
                                        Class<?> rowMapperClass, Logger logger) {
        this.databaseService = databaseService;
        this.db = db;
        this.async = async;
        this.update = update;
        this.query = query;
        this.tableName = tableName;
        this.rowMapperClass = rowMapperClass;
        this.logger = logger;
    }

    public static Optional<PlayerDataRepository> resolve(HexCoreBridge bridge, Logger logger) {
        if (bridge == null) return Optional.empty();
        Optional<Object> dsOpt = bridge.databaseService();
        if (dsOpt.isEmpty()) return Optional.empty();
        Object ds = dsOpt.get();
        try {
            ClassLoader cl = ds.getClass().getClassLoader();
            Class<?> dsIface = Class.forName("hex.core.api.db.DatabaseService", false, cl);
            Class<?> dbIface = Class.forName("hex.core.api.db.Db", false, cl);
            Class<?> rowMapper = Class.forName("hex.core.api.db.RowMapper", false, cl);
            Method dbGetter = dsIface.getMethod("db");
            Method async = dsIface.getMethod("async", Supplier.class);
            Method update = dbIface.getMethod("update", String.class, Object[].class);
            Method query = dbIface.getMethod("query", String.class, rowMapper, Object[].class);
            Method tableName = dbIface.getMethod("t", String.class);
            Object db = dbGetter.invoke(ds);
            if (db == null) return Optional.empty();
            return Optional.of(new HexCorePlayerDataRepository(ds, db, async, update, query, tableName, rowMapper, logger));
        } catch (Throwable t) {
            if (logger != null) logger.warning("HexNPC: player-data DB unavailable: " + safeMessage(t));
            return Optional.empty();
        }
    }

    @Override
    public CompletableFuture<Void> init() {
        return submit(() -> {
            String table = table();
            execUpdate("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "player_uuid VARCHAR(36) NOT NULL,"
                    + "data_key VARCHAR(128) NOT NULL,"
                    + "data_value TEXT NULL,"
                    + "updated_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (player_uuid, data_key)"
                    + ")");
            return null;
        });
    }

    @Override
    public CompletableFuture<Map<String, String>> load(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(Map.of());
        return submit(() -> {
            Map<String, String> values = new LinkedHashMap<>();
            Object mapper = Proxy.newProxyInstance(
                    rowMapperClass.getClassLoader(),
                    new Class<?>[]{rowMapperClass},
                    (proxy, method, args) -> {
                        if ("map".equals(method.getName()) && args != null && args.length == 1) {
                            ResultSet rs = (ResultSet) args[0];
                            return new java.util.AbstractMap.SimpleImmutableEntry<>(
                                    rs.getString("data_key"), rs.getString("data_value"));
                        }
                        if ("toString".equals(method.getName())) return "HexNPCPlayerDataRowMapper";
                        return null;
                    });
            @SuppressWarnings("unchecked")
            List<Map.Entry<String, String>> rows = (List<Map.Entry<String, String>>) invoke(query, db,
                    "SELECT data_key, data_value FROM " + table() + " WHERE player_uuid=?",
                    mapper,
                    new Object[]{playerId.toString()});
            if (rows != null) {
                for (Map.Entry<String, String> row : rows) {
                    if (row != null && row.getKey() != null) values.put(row.getKey(), row.getValue() == null ? "" : row.getValue());
                }
            }
            return Map.copyOf(values);
        });
    }

    @Override
    public CompletableFuture<Void> set(UUID playerId, String key, String value) {
        if (playerId == null || key == null || key.isBlank()) return failed(new IllegalArgumentException("invalid player data key"));
        return submit(() -> {
            execUpdate("INSERT INTO " + table() + " (player_uuid,data_key,data_value,updated_at) VALUES (?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE data_value=VALUES(data_value), updated_at=VALUES(updated_at)",
                    playerId.toString(), key, value == null ? "" : value, System.currentTimeMillis());
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> delete(UUID playerId, String key) {
        if (playerId == null || key == null || key.isBlank()) return failed(new IllegalArgumentException("invalid player data key"));
        return submit(() -> {
            execUpdate("DELETE FROM " + table() + " WHERE player_uuid=? AND data_key=?", playerId.toString(), key);
            return null;
        });
    }

    @Override
    public boolean available() {
        return true;
    }

    private String table() {
        try {
            Object result = tableName.invoke(db, TABLE);
            return result == null ? TABLE : result.toString();
        } catch (Throwable t) {
            return TABLE;
        }
    }

    private int execUpdate(String sql, Object... params) throws Exception {
        Object result = invoke(update, db, sql, params);
        return result instanceof Number number ? number.intValue() : 0;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /**
     * HexCore exposes async(Supplier), whose Supplier cannot declare checked exceptions.
     * Repository operations use reflection/JDBC and can legitimately throw Exception, so
     * adapt them once here and preserve the failure on the returned CompletableFuture.
     */
    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> submit(ThrowingSupplier<T> operation) {
        Supplier<T> supplier = () -> {
            try {
                return operation.get();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new CompletionException(ex);
            }
        };

        try {
            Object out = async.invoke(databaseService, supplier);
            if (out instanceof CompletableFuture<?> cf) return (CompletableFuture<T>) cf;
            return failed(new IllegalStateException("HexCore DatabaseService.async returned non-future"));
        } catch (Throwable t) {
            if (logger != null) logger.fine("HexNPC: player-data async submit failed: " + safeMessage(t));
            return failed(t);
        }
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw ex;
        }
    }

    private static <T> CompletableFuture<T> failed(Throwable t) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(t);
        return future;
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "unknown";
        Throwable current = t;
        while (current instanceof InvocationTargetException ite && ite.getCause() != null) current = ite.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
