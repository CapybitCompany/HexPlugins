package hexabovename.repository;

import hexabovename.config.HexAboveNameConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class MySqlDisplayTextRepository implements DisplayTextRepository {

    private final String tableQuoted;
    private final String playerColumnQuoted;
    private final String uuidColumnQuoted;
    private final String textColumnQuoted;
    private final String playerColumn;
    private final String uuidColumn;
    private final String textColumn;
    private HexCoreDbBridge dbBridge;

    public MySqlDisplayTextRepository(HexAboveNameConfig.MySql config) {
        this.tableQuoted = quoteIdentifier(config.table());
        this.playerColumnQuoted = quoteIdentifier(config.columns().player());
        this.uuidColumnQuoted = quoteIdentifier(config.columns().uuid());
        this.textColumnQuoted = quoteIdentifier(config.columns().text());
        this.playerColumn = config.columns().player();
        this.uuidColumn = config.columns().uuid();
        this.textColumn = config.columns().text();
    }

    @Override
    public void initialize() throws Exception {
        this.dbBridge = HexCoreDbBridge.connect();
        String sql = "SELECT " + uuidColumnQuoted + " FROM " + tableQuoted + " LIMIT 1";
        requireBridge().query(sql, rs -> rs.getString(1));
    }

    @Override
    public Map<UUID, String> loadDisplayTexts(Collection<PlayerSnapshot> players) throws Exception {
        if (players.isEmpty()) {
            return Map.of();
        }

        List<String> uuids = new ArrayList<>(players.size());
        List<String> names = new ArrayList<>(players.size());
        Map<String, UUID> uuidByLowerName = new HashMap<>(players.size());
        Set<UUID> onlineUuids = players.stream().map(PlayerSnapshot::uuid).collect(Collectors.toSet());

        for (PlayerSnapshot player : players) {
            uuids.add(player.uuid().toString());
            names.add(player.name());
            uuidByLowerName.put(player.name().toLowerCase(Locale.ROOT), player.uuid());
        }

        String sql = "SELECT " + playerColumnQuoted + ", " + uuidColumnQuoted + ", " + textColumnQuoted
                + " FROM " + tableQuoted
                + " WHERE " + uuidColumnQuoted + " IN (" + placeholders(uuids.size()) + ")"
                + " OR " + playerColumnQuoted + " IN (" + placeholders(names.size()) + ")";

        Map<UUID, String> result = new HashMap<>();
        Object[] params = new Object[uuids.size() + names.size()];
        int parameter = 0;
        for (String uuid : uuids) {
            params[parameter++] = uuid;
        }
        for (String player : names) {
            params[parameter++] = player;
        }

        List<RowData> rows = requireBridge().query(sql, rs -> new RowData(
                rs.getString(playerColumn),
                rs.getString(uuidColumn),
                rs.getString(textColumn)
        ), params);

        for (RowData row : rows) {
            if (row.text() == null || row.text().isBlank()) {
                continue;
            }

            UUID resolvedUuid = resolveUuid(row, onlineUuids, uuidByLowerName);
            if (resolvedUuid == null) {
                continue;
            }
            result.put(resolvedUuid, row.text());
        }
        return result;
    }

    @Override
    public void upsertDisplayText(UUID uuid, String playerName, String text) throws Exception {
        String sql = "INSERT INTO " + tableQuoted + " (" + uuidColumnQuoted + ", " + playerColumnQuoted + ", " + textColumnQuoted + ") "
                + "VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE "
                + playerColumnQuoted + " = VALUES(" + playerColumnQuoted + "), "
                + textColumnQuoted + " = VALUES(" + textColumnQuoted + ")";
        requireBridge().update(sql, uuid.toString(), playerName, text);
    }

    @Override
    public void clearDisplayText(UUID uuid, String playerName) throws Exception {
        String sql = "DELETE FROM " + tableQuoted + " WHERE " + uuidColumnQuoted + " = ? OR " + playerColumnQuoted + " = ?";
        requireBridge().update(sql, uuid.toString(), playerName);
    }

    @Override
    public void close() {
        this.dbBridge = null;
    }

    private HexCoreDbBridge requireBridge() {
        if (dbBridge == null) {
            throw new IllegalStateException("HexCore DB bridge nie jest zainicjalizowany.");
        }
        return dbBridge;
    }

    private UUID resolveUuid(RowData row, Set<UUID> onlineUuids, Map<String, UUID> uuidByLowerName) {
        String uuidRaw = row.uuid();
        if (uuidRaw != null && !uuidRaw.isBlank()) {
            try {
                UUID uuid = UUID.fromString(uuidRaw);
                if (onlineUuids.contains(uuid)) {
                    return uuid;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        String player = row.player();
        if (player == null || player.isBlank()) {
            return null;
        }
        return uuidByLowerName.get(player.toLowerCase(Locale.ROOT));
    }

    private static String placeholders(int count) {
        StringBuilder builder = new StringBuilder(Math.max(2, count * 2));
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append('?');
        }
        return builder.toString();
    }

    private static String quoteIdentifier(String identifier) {
        if (!identifier.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Niepoprawny identyfikator SQL: " + identifier);
        }
        return '`' + identifier + '`';
    }

    private record RowData(
            String player,
            String uuid,
            String text
    ) {
    }

    @FunctionalInterface
    private interface SqlRowMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }

    private static final class HexCoreDbBridge {
        private final Object dbClient;
        private final Class<?> rowMapperClass;
        private final Method queryMethod;
        private final Method updateMethod;

        private HexCoreDbBridge(Object dbClient, Class<?> rowMapperClass, Method queryMethod, Method updateMethod) {
            this.dbClient = dbClient;
            this.rowMapperClass = rowMapperClass;
            this.queryMethod = queryMethod;
            this.updateMethod = updateMethod;
        }

        static HexCoreDbBridge connect() throws Exception {
            Class<?> hexApiClass = Class.forName("hex.core.api.HexApi");
            Class<?> databaseServiceClass = Class.forName("hex.core.api.db.DatabaseService");
            Class<?> dbClass = Class.forName("hex.core.api.db.Db");
            @SuppressWarnings("unchecked")
            RegisteredServiceProvider<Object> registration =
                    (RegisteredServiceProvider<Object>) Bukkit.getServicesManager().getRegistration((Class<Object>) hexApiClass);

            if (registration == null || registration.getProvider() == null) {
                throw new IllegalStateException("Nie znaleziono HexCore API w ServicesManager.");
            }

            Object api = registration.getProvider();
            Method dbMethod = hexApiClass.getMethod("db");
            Object dbService = invokeNoArgs(api, dbMethod);
            if (dbService == null) {
                throw new IllegalStateException("HexCore db() zwróciło null.");
            }

            Method dbClientMethod = databaseServiceClass.getMethod("db");
            Object dbClient = invokeNoArgs(dbService, dbClientMethod);
            if (dbClient == null) {
                throw new IllegalStateException("HexCore db().db() zwróciło null.");
            }

            Class<?> rowMapperClass = Class.forName("hex.core.api.db.RowMapper");
            Method queryMethod = dbClass.getMethod("query", String.class, rowMapperClass, Object[].class);
            Method updateMethod = dbClass.getMethod("update", String.class, Object[].class);
            return new HexCoreDbBridge(dbClient, rowMapperClass, queryMethod, updateMethod);
        }

        int update(String sql, Object... params) throws Exception {
            try {
                Object output = updateMethod.invoke(dbClient, sql, params);
                return output instanceof Integer value ? value : 0;
            } catch (InvocationTargetException exception) {
                throw unwrapInvocation(exception);
            }
        }

        @SuppressWarnings("unchecked")
        <T> List<T> query(String sql, SqlRowMapper<T> mapper, Object... params) throws Exception {
            Object mapperProxy = Proxy.newProxyInstance(
                    rowMapperClass.getClassLoader(),
                    new Class<?>[]{rowMapperClass},
                    (proxy, method, args) -> {
                        String methodName = method.getName();
                        if ("map".equals(methodName)) {
                            return mapper.map((ResultSet) args[0]);
                        }
                        if ("toString".equals(methodName)) {
                            return "HexAboveNameSqlRowMapper";
                        }
                        if ("hashCode".equals(methodName)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(methodName)) {
                            return proxy == args[0];
                        }
                        throw new UnsupportedOperationException("Nieobsługiwana metoda RowMapper: " + methodName);
                    }
            );

            try {
                Object output = queryMethod.invoke(dbClient, sql, mapperProxy, params);
                if (output instanceof List<?> list) {
                    return (List<T>) list;
                }
                return List.of();
            } catch (InvocationTargetException exception) {
                throw unwrapInvocation(exception);
            }
        }

        private static Object invokeNoArgs(Object target, Method method) throws Exception {
            try {
                return method.invoke(target);
            } catch (InvocationTargetException exception) {
                throw unwrapInvocation(exception);
            }
        }

        private static Exception unwrapInvocation(InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception wrapped) {
                return wrapped;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            return new RuntimeException(cause == null ? exception : cause);
        }
    }
}
