package hex.core.service.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import hex.core.api.db.DatabaseService;
import hex.core.api.db.Db;
import hex.core.api.db.RowMapper;
import hex.core.api.db.SqlException;
import org.bukkit.plugin.Plugin;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.function.Supplier;

public final class HikariDatabaseService implements DatabaseService {

    private final Plugin plugin;
    private final DbConfig cfg;
    private final HikariDataSource ds;
    private final ExecutorService executor;
    private final Db client;

    public HikariDatabaseService(Plugin plugin, DbConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("HexCore-DB");
        hc.setMaximumPoolSize(cfg.pool.maxSize);
        hc.setMinimumIdle(cfg.pool.minIdle);
        hc.setConnectionTimeout(cfg.pool.timeoutMs);
        hc.setMaxLifetime(cfg.pool.lifetimeMs);

        if (isSqlite(cfg)) {
            String path = new java.io.File(plugin.getDataFolder(), cfg.sqliteFile).getAbsolutePath();
            hc.setJdbcUrl("jdbc:sqlite:" + path);
        } else {
            // mysql/mariadb
            String driver = cfg.type.equalsIgnoreCase("mariadb") ? "mariadb" : "mysql";
            String jdbc = "jdbc:" + driver + "://" + cfg.host + ":" + cfg.port + "/" + cfg.database
                    + "?useSSL=" + cfg.options.useSSL
                    + "&serverTimezone=" + cfg.options.serverTimezone
                    + "&characterEncoding=utf8"
                    + "&useUnicode=true";
            hc.setJdbcUrl(jdbc);
            hc.setUsername(cfg.username);
            hc.setPassword(cfg.password);
        }

        this.ds = new HikariDataSource(hc);

        this.executor = Executors.newFixedThreadPool(
                Math.max(2, Math.min(6, cfg.pool.maxSize)),
                r -> {
                    Thread t = new Thread(r, "HexCore-DB");
                    t.setDaemon(true);
                    return t;
                }
        );

        this.client = new DbImpl(ds, cfg.tablePrefix);
    }

    private static boolean isSqlite(DbConfig c) {
        return "sqlite".equalsIgnoreCase(c.type);
    }

    @Override
    public Db db() {
        return client;
    }

    @Override
    public <T> CompletableFuture<T> async(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, executor)
                .exceptionally(ex -> {
                    plugin.getLogger().severe("[DB] Async error: " + ex.getMessage());
                    throw (ex instanceof RuntimeException re) ? re : new RuntimeException(ex);
                });
    }

    @Override
    public void shutdown() {
        executor.shutdownNow();
        ds.close();
    }

    public javax.sql.DataSource dataSource() {
        return ds;
    }

    private static final class DbImpl implements Db {
        private final HikariDataSource ds;
        private final String prefix;

        DbImpl(HikariDataSource ds, String prefix) {
            this.ds = ds;
            this.prefix = prefix == null ? "" : prefix;
        }

        @Override public String tablePrefix() { return prefix; }

        @Override
        public int update(String sql, Object... params) {
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw wrap("update", sql, e);
            }
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
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
            List<T> list = query(sql, mapper, params);
            if (list.isEmpty()) return Optional.empty();
            return Optional.of(list.get(0));
        }

        @Override
        public int[] batch(String sql, List<Object[]> batchParams) {
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
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
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
        }

        private static SqlException wrap(String op, String sql, SQLException e) {
            String safe = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
            if (safe.length() > 220) safe = safe.substring(0, 220) + "...";
            return new SqlException("DB " + op + " failed: " + safe, e);
        }
    }

    /** Db bound to a single Connection (transaction). */
    private static final class TxDb implements Db {
        private final Connection c;
        private final String prefix;

        TxDb(Connection c, String prefix) {
            this.c = c;
            this.prefix = prefix == null ? "" : prefix;
        }

        @Override public String tablePrefix() { return prefix; }

        @Override
        public int update(String sql, Object... params) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw DbImpl.wrap("update(tx)", sql, e);
            }
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, params);
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
            List<T> list = query(sql, mapper, params);
            if (list.isEmpty()) return Optional.empty();
            return Optional.of(list.get(0));
        }

        @Override
        public int[] batch(String sql, List<Object[]> batchParams) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (Object[] p : batchParams) {
                    bind(ps, p);
                    ps.addBatch();
                }
                return ps.executeBatch();
            } catch (SQLException e) {
                throw DbImpl.wrap("batch(tx)", sql, e);
            }
        }

        @Override
        public <T> T tx(Function<Db, T> work) {
            // already in tx
            return work.apply(this);
        }

        private static void bind(PreparedStatement ps, Object... params) throws SQLException {
            if (params == null) return;
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
        }
    }
}
