package hexbuildbattle.statistics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class SQLiteStatisticsRepository implements StatisticsRepository {

    private final Path databaseFile;
    private final Logger logger;
    private final ExecutorService executor;

    public SQLiteStatisticsRepository(Path databaseFile, Logger logger) {
        this.databaseFile = databaseFile;
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "HexBuildBattle-SQLite");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            ensureDriver();
            ensureParent();
            try (Connection connection = open();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS hexbuildbattle_statistics (
                            uuid TEXT PRIMARY KEY,
                            last_known_name TEXT NOT NULL,
                            ranking_points INTEGER NOT NULL DEFAULT 0,
                            games_played INTEGER NOT NULL DEFAULT 0,
                            wins INTEGER NOT NULL DEFAULT 0,
                            podiums INTEGER NOT NULL DEFAULT 0,
                            total_build_score INTEGER NOT NULL DEFAULT 0,
                            total_votes_received INTEGER NOT NULL DEFAULT 0
                        )
                        """);
            } catch (SQLException ex) {
                throw new IllegalStateException("Could not initialize SQLite statistics database.", ex);
            }
        }, executor).whenComplete((ignored, failure) -> {
            if (failure != null) {
                logger.severe("HexBuildBattle SQLite init failed: " + failure.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<List<PlayerStatistics>> loadAll() {
        return CompletableFuture.supplyAsync(() -> {
            List<PlayerStatistics> statistics = new ArrayList<>();
            String sql = """
                    SELECT uuid, last_known_name, ranking_points, games_played, wins, podiums,
                           total_build_score, total_votes_received
                    FROM hexbuildbattle_statistics
                    ORDER BY ranking_points DESC, wins DESC, podiums DESC, last_known_name ASC
                    """;
            try (Connection connection = open();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                int rank = 1;
                while (result.next()) {
                    statistics.add(new PlayerStatistics(
                            UUID.fromString(result.getString("uuid")),
                            result.getString("last_known_name"),
                            result.getLong("ranking_points"),
                            result.getInt("games_played"),
                            result.getInt("wins"),
                            result.getInt("podiums"),
                            result.getLong("total_build_score"),
                            result.getLong("total_votes_received"),
                            rank++
                    ));
                }
            } catch (SQLException ex) {
                throw new IllegalStateException("Could not load SQLite statistics.", ex);
            }
            return statistics;
        }, executor).whenComplete((ignored, failure) -> {
            if (failure != null) {
                logger.warning("HexBuildBattle statistics load failed: " + failure.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Void> applyRoundUpdates(List<StatisticsUpdate> updates) {
        if (updates.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            String sql = """
                    INSERT INTO hexbuildbattle_statistics (
                        uuid, last_known_name, ranking_points, games_played, wins, podiums,
                        total_build_score, total_votes_received
                    ) VALUES (?, ?, ?, 1, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        last_known_name = excluded.last_known_name,
                        ranking_points = ranking_points + excluded.ranking_points,
                        games_played = games_played + 1,
                        wins = wins + excluded.wins,
                        podiums = podiums + excluded.podiums,
                        total_build_score = total_build_score + excluded.total_build_score,
                        total_votes_received = total_votes_received + excluded.total_votes_received
                    """;
            try (Connection connection = open();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                connection.setAutoCommit(false);
                for (StatisticsUpdate update : updates) {
                    statement.setString(1, update.playerId().toString());
                    statement.setString(2, update.lastKnownName());
                    statement.setInt(3, update.rankingPoints());
                    statement.setInt(4, update.win() ? 1 : 0);
                    statement.setInt(5, update.podium() ? 1 : 0);
                    statement.setInt(6, update.buildScore());
                    statement.setInt(7, update.votesReceived());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException ex) {
                throw new IllegalStateException("Could not save SQLite statistics.", ex);
            }
        }, executor).whenComplete((ignored, failure) -> {
            if (failure != null) {
                logger.warning("HexBuildBattle statistics update failed: " + failure.getMessage());
            }
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
    }

    private void ensureDriver() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("SQLite JDBC driver is missing. Paper should load it from plugin.yml libraries.", ex);
        }
    }

    private void ensureParent() {
        try {
            Files.createDirectories(databaseFile.toAbsolutePath().getParent());
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create database folder.", ex);
        }
    }
}
