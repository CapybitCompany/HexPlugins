package hex.core.service.db;

import hex.core.api.db.Db;
import hex.core.api.db.RowMapper;
import hex.core.api.db.SqlException;
import org.bukkit.plugin.Plugin;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Fallback DB implementation used when HikariCP is not available in the plugin classloader.
 * It opens short-lived JDBC connections through DriverManager, so it is slower than Hikari,
 * but keeps HexCore bootable and functional on servers with broken shaded/library setup.
 */
public final class DriverManagerDatabaseService implements DataSourceBackedDatabaseService {
    private final Plugin plugin;
    private final DriverManagerDataSource dataSource;
    private final ExecutorService executor;
    private final Db client;

    public DriverManagerDatabaseService(Plugin plugin, DbConfig cfg) {
        this.plugin = plugin;
        JdbcSettings settings = settings(plugin, cfg);
        loadDriver(settings.driverClassName());
        this.dataSource = new DriverManagerDataSource(settings.jdbcUrl(), settings.username(), settings.password());
        this.executor = Executors.newFixedThreadPool(
                Math.max(2, Math.min(6, cfg.pool.maxSize)),
                r -> {
                    Thread t = new Thread(r, "HexCore-DB-Fallback");
                    t.setDaemon(true);
                    return t;
                }
        );
        this.client = new DbImpl(dataSource, cfg.tablePrefix);
    }

    @Override
    public Db db() {
        return client;
    }

    @Override
    public <T> CompletableFuture<T> async(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, executor)
                .exceptionally(ex -> {
                    plugin.getLogger().severe("[DB] Async fallback error: " + ex.getMessage());
                    throw (ex instanceof RuntimeException re) ? re : new RuntimeException(ex);
                });
    }

    @Override
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public DataSource dataSource() {
        return dataSource;
    }

    private static JdbcSettings settings(Plugin plugin, DbConfig cfg) {
        if ("sqlite".equalsIgnoreCase(cfg.type)) {
            String path = new java.io.File(plugin.getDataFolder(), cfg.sqliteFile).getAbsolutePath();
            return new JdbcSettings("org.sqlite.JDBC", "jdbc:sqlite:" + path, null, null);
        }
        boolean mariadb = "mariadb".equalsIgnoreCase(cfg.type);
        String driver = mariadb ? "mariadb" : "mysql";
        String driverClass = mariadb ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver";
        String jdbc = "jdbc:" + driver + "://" + cfg.host + ":" + cfg.port + "/" + cfg.database
                + "?useSSL=" + cfg.options.useSSL
                + "&serverTimezone=" + cfg.options.serverTimezone
                + "&characterEncoding=utf8"
                + "&useUnicode=true";
        return new JdbcSettings(driverClass, jdbc, cfg.username, cfg.password);
    }

    private static void loadDriver(String driverClassName) {
        try {
            Class.forName(driverClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Missing JDBC driver: " + driverClassName, e);
        }
    }

    private record JdbcSettings(String driverClassName, String jdbcUrl, String username, String password) {}

    private static final class DriverManagerDataSource implements DataSource {
        private final String jdbcUrl;
        private final String username;
        private final String password;
        private volatile PrintWriter logWriter;
        private volatile int loginTimeout;

        private DriverManagerDataSource(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (username == null || username.isBlank()) return DriverManager.getConnection(jdbcUrl);
            return DriverManager.getConnection(jdbcUrl, username, password == null ? "" : password);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(jdbcUrl, username, password);
        }

        @Override public PrintWriter getLogWriter() { return logWriter; }
        @Override public void setLogWriter(PrintWriter out) { this.logWriter = out; }
        @Override public void setLoginTimeout(int seconds) { this.loginTimeout = seconds; DriverManager.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() { return loginTimeout; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { if (iface.isInstance(this)) return iface.cast(this); throw new SQLException("Not a wrapper for " + iface); }
        @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
    }

    private static final class DbImpl implements Db {
        private final DataSource ds;
        private final String prefix;

        private DbImpl(DataSource ds, String prefix) {
            this.ds = ds;
            this.prefix = prefix == null ? "" : prefix;
        }

        @Override public String tablePrefix() { return prefix; }

        @Override
        public int update(String sql, Object... params) {
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw wrap("update", sql, e);
            }
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> out = new ArrayList<>();
                    while (rs.next()) out.add(mapper.map(rs));
                    return out;
                }
            } catch (SQLException e) {
                throw wrap("query", sql, e);
            }
        }

        @Override
        public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setMaxRows(1);
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.ofNullable(mapper.map(rs));
                }
            } catch (SQLException e) {
                throw wrap("queryOne", sql, e);
            }
        }

        @Override
        public int[] batch(String sql, List<Object[]> batchParams) {
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                for (Object[] p : batchParams) {
                    bind(ps, p);
                    ps.addBatch();
                }
                return ps.executeBatch();
            } catch (SQLException e) {
                throw wrap("batch", sql, e);
            }
        }

        @Override
        public <T> T tx(Function<Db, T> work) {
            try (Connection c = ds.getConnection()) {
                boolean oldAuto = c.getAutoCommit();
                c.setAutoCommit(false);
                Db txDb = new TxDb(c, prefix);
                try {
                    T res = work.apply(txDb);
                    c.commit();
                    return res;
                } catch (Exception ex) {
                    c.rollback();
                    throw ex;
                } finally {
                    c.setAutoCommit(oldAuto);
                }
            } catch (SQLException e) {
                throw wrap("tx", "<transaction>", e);
            }
        }

        private static void bind(PreparedStatement ps, Object... params) throws SQLException {
            if (params == null) return;
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
        }

        private static SqlException wrap(String op, String sql, SQLException e) {
            String safe = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
            if (safe.length() > 220) safe = safe.substring(0, 220) + "...";
            return new SqlException("DB " + op + " failed: " + safe
                    + " | SQLState=" + e.getSQLState()
                    + " | code=" + e.getErrorCode()
                    + " | cause=" + e.getMessage(), e);
        }
    }

    private static final class TxDb implements Db {
        private final Connection c;
        private final String prefix;

        private TxDb(Connection c, String prefix) {
            this.c = c;
            this.prefix = prefix == null ? "" : prefix;
        }

        @Override public String tablePrefix() { return prefix; }

        @Override
        public int update(String sql, Object... params) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                DbImpl.bind(ps, params);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw DbImpl.wrap("update(tx)", sql, e);
            }
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                DbImpl.bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> out = new ArrayList<>();
                    while (rs.next()) out.add(mapper.map(rs));
                    return out;
                }
            } catch (SQLException e) {
                throw DbImpl.wrap("query(tx)", sql, e);
            }
        }

        @Override
        public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setMaxRows(1);
                DbImpl.bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.ofNullable(mapper.map(rs));
                }
            } catch (SQLException e) {
                throw DbImpl.wrap("queryOne(tx)", sql, e);
            }
        }

        @Override
        public int[] batch(String sql, List<Object[]> batchParams) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (Object[] p : batchParams) {
                    DbImpl.bind(ps, p);
                    ps.addBatch();
                }
                return ps.executeBatch();
            } catch (SQLException e) {
                throw DbImpl.wrap("batch(tx)", sql, e);
            }
        }

        @Override
        public <T> T tx(Function<Db, T> work) {
            return work.apply(this);
        }
    }
}

