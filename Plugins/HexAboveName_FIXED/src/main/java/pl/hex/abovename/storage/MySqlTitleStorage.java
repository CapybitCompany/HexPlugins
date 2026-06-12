package pl.hex.abovename.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * MySQL-backed title storage owned entirely by HexAboveName.
 *
 * Threading: every public CompletableFuture-returning method runs on a
 * dedicated single-threaded executor. The pool itself is sized via the
 * {@link MySqlConfig#maximumPoolSize()} setting, but for write throughput
 * we don't need parallel DB threads — one async worker is enough and keeps
 * ordering deterministic for the cache.
 */
public final class MySqlTitleStorage implements TitleStorage {

    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS %s (" +
                    "uuid VARCHAR(36) PRIMARY KEY," +
                    "name VARCHAR(16) NOT NULL," +
                    "title TEXT NOT NULL," +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";

    /** Identifiers cannot be bound as PreparedStatement params, so we validate. */
    private static final Pattern VALID_TABLE_NAME = Pattern.compile("[A-Za-z0-9_]+");

    private final MySqlConfig config;
    private final Logger logger;
    private final HikariDataSource dataSource;
    private final ExecutorService executor;

    public MySqlTitleStorage(MySqlConfig config, Logger logger) {
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        if (config.table() == null || !VALID_TABLE_NAME.matcher(config.table()).matches()) {
            throw new IllegalArgumentException(
                    "Invalid storage.mysql.table '" + config.table()
                            + "' (allowed: A-Z a-z 0-9 _)");
        }
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.jdbcUrl());
        hc.setUsername(config.username());
        hc.setPassword(config.password() == null ? "" : config.password());
        hc.setMaximumPoolSize(Math.max(1, config.maximumPoolSize()));
        hc.setMinimumIdle(Math.max(0, config.minimumIdle()));
        hc.setConnectionTimeout(Math.max(1000L, config.connectionTimeoutMs()));
        hc.setMaxLifetime(Math.max(30_000L, config.maxLifetimeMs()));
        hc.setPoolName("hex-above-name");
        hc.setAutoCommit(true);
        // -1 = lazy init. Skip the up-front connection attempt so constructing
        // this storage on the Bukkit main thread cannot block on the network.
        // The real connectivity probe happens inside ensureSchema(), which runs
        // entirely on the executor below.
        hc.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(hc);
        this.executor = Executors.newSingleThreadExecutor(namedThreads("hex-above-name-db"));
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private String table() {
        return config.table();
    }

    @Override
    public CompletableFuture<Void> ensureSchema() {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         String.format(CREATE_TABLE_SQL, table()))) {
                stmt.executeUpdate();
            } catch (SQLException ex) {
                throw new RuntimeException("ensureSchema failed: " + ex.getMessage(), ex);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Map<UUID, StoredTitle>> loadAll() {
        return CompletableFuture.supplyAsync(() -> {
            Map<UUID, StoredTitle> out = new HashMap<>();
            String sql = "SELECT uuid, name, title FROM " + table();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        String name = rs.getString("name");
                        String title = rs.getString("title");
                        if (name == null || title == null) continue;
                        out.put(uuid, new StoredTitle(uuid, name, title));
                    } catch (IllegalArgumentException ignored) {
                        // skip malformed UUIDs
                    }
                }
            } catch (SQLException ex) {
                throw new RuntimeException("loadAll failed: " + ex.getMessage(), ex);
            }
            return out;
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<StoredTitle>> load(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT uuid, name, title FROM " + table() + " WHERE uuid=?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<StoredTitle>empty();
                    }
                    String name = rs.getString("name");
                    String title = rs.getString("title");
                    if (name == null || title == null) {
                        return Optional.<StoredTitle>empty();
                    }
                    return Optional.of(new StoredTitle(uuid, name, title));
                }
            } catch (SQLException ex) {
                throw new RuntimeException("load failed: " + ex.getMessage(), ex);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> save(UUID uuid, String playerName, String title) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO " + table() + " (uuid, name, title) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE name=VALUES(name), title=VALUES(title), updated_at=CURRENT_TIMESTAMP";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, playerName);
                stmt.setString(3, title);
                stmt.executeUpdate();
            } catch (SQLException ex) {
                throw new RuntimeException("save failed: " + ex.getMessage(), ex);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> delete(UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM " + table() + " WHERE uuid=?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException ex) {
                throw new RuntimeException("delete failed: " + ex.getMessage(), ex);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<UUID>> findUuidByName(String name) {
        if (name == null || name.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT uuid FROM " + table() + " WHERE LOWER(name)=LOWER(?) LIMIT 1";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, name);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<UUID>empty();
                    }
                    try {
                        return Optional.of(UUID.fromString(rs.getString("uuid")));
                    } catch (IllegalArgumentException ex) {
                        return Optional.<UUID>empty();
                    }
                }
            } catch (SQLException ex) {
                throw new RuntimeException("findUuidByName failed: " + ex.getMessage(), ex);
            }
        }, executor);
    }

    @Override
    public void close() {
        try {
            executor.shutdown();
        } catch (Exception ignored) {
        }
        try {
            dataSource.close();
        } catch (Exception ex) {
            logger.log(Level.WARNING, "HexAboveName: error closing Hikari datasource: " + ex.getMessage());
        }
    }
}
