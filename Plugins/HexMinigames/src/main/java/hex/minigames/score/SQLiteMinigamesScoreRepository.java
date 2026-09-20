package hex.minigames.score;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

public final class SQLiteMinigamesScoreRepository implements MinigamesScoreRepository {
    private final Plugin plugin;
    private final File databaseFile;
    private boolean available;

    public SQLiteMinigamesScoreRepository(Plugin plugin, String sqliteFile) {
        this.plugin = plugin;
        this.databaseFile = new File(plugin.getDataFolder(), sqliteFile == null || sqliteFile.isBlank() ? "minigames.db" : sqliteFile);
    }

    @Override
    public void initialize() {
        plugin.getDataFolder().mkdirs();
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS minigames_scores (
                          player_uuid TEXT PRIMARY KEY,
                          points INTEGER NOT NULL DEFAULT 0,
                          updated_at INTEGER NOT NULL
                        )
                        """);
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS minigames_series_commits (
                          session_id TEXT NOT NULL,
                          player_uuid TEXT NOT NULL,
                          points INTEGER NOT NULL,
                          created_at INTEGER NOT NULL,
                          PRIMARY KEY (session_id, player_uuid)
                        )
                        """);
            }
            try (Connection connection = connect(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS minigames_player_names (player_uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL)");
            }
            available = true;
        } catch (Throwable error) {
            available = false;
            plugin.getLogger().severe("SQLite minigames score storage is unavailable: " + rootMessage(error));
        }
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String backendName() {
        return "sqlite";
    }

    @Override
    public synchronized boolean commitSeriesPoints(UUID sessionId, UUID playerId, int points) {
        ensureAvailable();
        try (Connection connection = connect()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                boolean inserted;
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT OR IGNORE INTO minigames_series_commits
                        (session_id, player_uuid, points, created_at)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    insert.setString(1, sessionId.toString());
                    insert.setString(2, playerId.toString());
                    insert.setInt(3, points);
                    insert.setLong(4, System.currentTimeMillis());
                    inserted = insert.executeUpdate() == 1;
                }
                if (inserted && points != 0) addScore(connection, playerId, points);
                connection.commit();
                connection.setAutoCommit(oldAutoCommit);
                return inserted;
            } catch (Exception error) {
                connection.rollback();
                connection.setAutoCommit(oldAutoCommit);
                throw error;
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not commit minigames series points", error);
        }
    }

    @Override
    public synchronized int globalPoints(UUID playerId) {
        ensureAvailable();
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement("SELECT points FROM minigames_scores WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt("points") : 0;
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not read minigames points", error);
        }
    }

    @Override public synchronized java.util.List<LeaderboardEntry> allScores() {
        ensureAvailable();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT s.player_uuid, s.points, n.player_name FROM minigames_scores s LEFT JOIN minigames_player_names n ON s.player_uuid = n.player_uuid")) {
            var result = new java.util.ArrayList<LeaderboardEntry>();
            while (rs.next()) result.add(new LeaderboardEntry(UUID.fromString(rs.getString("player_uuid")), rs.getString("player_name"), rs.getInt("points")));
            return java.util.List.copyOf(result);
        } catch (Exception error) { throw new IllegalStateException("Could not load leaderboard", error); }
    }
    @Override public synchronized void saveName(UUID id, String name) {
        ensureAvailable();
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO minigames_player_names (player_uuid, player_name) VALUES (?, ?) ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name")) {
            statement.setString(1, id.toString()); statement.setString(2, name); statement.executeUpdate();
        } catch (Exception error) { throw new IllegalStateException("Could not save player name", error); }
    }
    @Override public synchronized void resetScores() {
        ensureAvailable();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM minigames_scores");
        } catch (Exception error) { throw new IllegalStateException("Could not reset leaderboard", error); }
    }

    private void addScore(Connection connection, UUID playerId, int points) throws Exception {
        int updated;
        try (PreparedStatement update = connection.prepareStatement("UPDATE minigames_scores SET points = points + ?, updated_at = ? WHERE player_uuid = ?")) {
            update.setInt(1, points);
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, playerId.toString());
            updated = update.executeUpdate();
        }
        if (updated == 0) {
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO minigames_scores (player_uuid, points, updated_at) VALUES (?, ?, ?)")) {
                insert.setString(1, playerId.toString());
                insert.setInt(2, points);
                insert.setLong(3, System.currentTimeMillis());
                insert.executeUpdate();
            }
        }
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
    }

    private void ensureAvailable() {
        if (!available) throw new IllegalStateException("Score storage is not available");
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
