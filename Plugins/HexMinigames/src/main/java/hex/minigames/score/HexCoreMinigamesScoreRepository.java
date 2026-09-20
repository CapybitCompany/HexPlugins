package hex.minigames.score;

import hex.core.api.db.Db;

import java.util.Optional;
import java.util.UUID;

public final class HexCoreMinigamesScoreRepository implements MinigamesScoreRepository {
    private final Db db;
    private boolean available;

    public HexCoreMinigamesScoreRepository(Db db) {
        this.db = db;
    }

    @Override
    public void initialize() {
        db.update("""
                CREATE TABLE IF NOT EXISTS %s (
                  player_uuid VARCHAR(36) PRIMARY KEY,
                  points INTEGER NOT NULL DEFAULT 0,
                  updated_at BIGINT NOT NULL
                )
                """.formatted(scoresTable()));
        db.update("""
                CREATE TABLE IF NOT EXISTS %s (
                  session_id VARCHAR(36) NOT NULL,
                  player_uuid VARCHAR(36) NOT NULL,
                  points INTEGER NOT NULL,
                  created_at BIGINT NOT NULL,
                  PRIMARY KEY (session_id, player_uuid)
                )
                """.formatted(commitsTable()));
        db.update("CREATE TABLE IF NOT EXISTS " + db.t("minigames_player_names")
                + " (player_uuid VARCHAR(36) PRIMARY KEY, player_name VARCHAR(64) NOT NULL)");
        available = true;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String backendName() {
        return "hexcore-db";
    }

    @Override
    public boolean commitSeriesPoints(UUID sessionId, UUID playerId, int points) {
        ensureAvailable();
        return db.tx(tx -> {
            Optional<Integer> existing = tx.queryOne(
                    "SELECT points FROM " + commitsTable() + " WHERE session_id = ? AND player_uuid = ?",
                    rs -> rs.getInt("points"),
                    sessionId.toString(),
                    playerId.toString()
            );
            if (existing.isPresent()) return false;
            tx.update(
                    "INSERT INTO " + commitsTable() + " (session_id, player_uuid, points, created_at) VALUES (?, ?, ?, ?)",
                    sessionId.toString(),
                    playerId.toString(),
                    points,
                    System.currentTimeMillis()
            );
            if (points != 0) addScore(tx, playerId, points);
            return true;
        });
    }

    @Override
    public int globalPoints(UUID playerId) {
        ensureAvailable();
        return db.queryOne(
                "SELECT points FROM " + scoresTable() + " WHERE player_uuid = ?",
                rs -> rs.getInt("points"),
                playerId.toString()
        ).orElse(0);
    }

    @Override public java.util.List<LeaderboardEntry> allScores() {
        ensureAvailable();
        return db.query("SELECT s.player_uuid, s.points, n.player_name FROM " + scoresTable()
                + " s LEFT JOIN " + db.t("minigames_player_names") + " n ON s.player_uuid = n.player_uuid",
                rs -> new LeaderboardEntry(UUID.fromString(rs.getString("player_uuid")), rs.getString("player_name"), rs.getInt("points")));
    }
    @Override public void saveName(UUID id, String name) {
        db.tx(tx -> {
            String table = db.t("minigames_player_names");
            if (tx.queryOne("SELECT player_uuid FROM " + table + " WHERE player_uuid = ?", rs -> rs.getString(1), id.toString()).isPresent())
                tx.update("UPDATE " + table + " SET player_name = ? WHERE player_uuid = ?", name, id.toString());
            else tx.update("INSERT INTO " + table + " (player_uuid, player_name) VALUES (?, ?)", id.toString(), name);
            return null;
        });
    }
    @Override public void resetScores() {
        ensureAvailable();
        // Keep the commit ledger so an already committed series cannot be awarded twice after reset.
        db.update("DELETE FROM " + scoresTable());
    }

    private void addScore(Db tx, UUID playerId, int points) {
        int updated = tx.update(
                "UPDATE " + scoresTable() + " SET points = points + ?, updated_at = ? WHERE player_uuid = ?",
                points,
                System.currentTimeMillis(),
                playerId.toString()
        );
        if (updated == 0) {
            tx.update(
                    "INSERT INTO " + scoresTable() + " (player_uuid, points, updated_at) VALUES (?, ?, ?)",
                    playerId.toString(),
                    points,
                    System.currentTimeMillis()
            );
        }
    }

    private String scoresTable() {
        return db.t("minigames_scores");
    }

    private String commitsTable() {
        return db.t("minigames_series_commits");
    }

    private void ensureAvailable() {
        if (!available) throw new IllegalStateException("HexCore score storage is not available");
    }
}
