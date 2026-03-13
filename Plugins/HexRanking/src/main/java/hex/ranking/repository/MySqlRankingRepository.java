package hex.ranking.repository;

import hex.core.api.db.Db;
import hex.ranking.model.PointsTable;
import hex.ranking.model.RankingPlayer;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class MySqlRankingRepository implements RankingRepository {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private final Db db;
    private final String rankingTable;

    public MySqlRankingRepository(Db db, String rankingTable) {
        this.db = db;
        this.rankingTable = requireIdentifier(rankingTable, "ranking table");
    }

    private String table() {
        return "`" + rankingTable + "`";
    }

    private static String requireIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + label + " name.");
        }
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + label + " name: " + value);
        }
        return value;
    }

    @Override
    public void upsertPlayer(UUID uuid, String playerName) {
        db.update(
                "INSERT INTO " + table() + " (uuid, player, global_points, season_points) VALUES (?, ?, 0, 0) " +
                        "ON DUPLICATE KEY UPDATE player = VALUES(player)",
                uuid.toString(), playerName
        );
    }

    @Override
    public void addPoints(UUID uuid, PointsTable pointsTable, int amount) {
        int globalDelta;
        int seasonDelta;
        String updateClause;

        if (pointsTable == PointsTable.SEASON) {
            globalDelta = amount;
            seasonDelta = amount;
            updateClause = "global_points = GREATEST(0, global_points + VALUES(global_points)), " +
                    "season_points = GREATEST(0, season_points + VALUES(season_points))";
        } else {
            globalDelta = amount;
            seasonDelta = 0;
            updateClause = "global_points = GREATEST(0, global_points + VALUES(global_points))";
        }

        db.update(
                "INSERT INTO " + table() + " (uuid, global_points, season_points) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE " + updateClause,
                uuid.toString(), globalDelta, seasonDelta
        );
    }

    @Override
    public void removePoints(UUID uuid, PointsTable pointsTable, int amount) {
        String pointsColumn = pointsTable.column();
        db.update(
                "UPDATE " + table() + " SET " +
                        pointsColumn + " = GREATEST(0, " + pointsColumn + " - ?) " +
                        "WHERE uuid = ?",
                amount, uuid.toString()
        );
    }

    @Override
    public int getPoints(UUID uuid, PointsTable pointsTable) {
        String pointsColumn = pointsTable.column();
        return db.queryOne(
                "SELECT " + pointsColumn + " FROM " + table() + " WHERE uuid=?",
                rs -> rs.getInt(pointsColumn),
                uuid.toString()
        ).orElse(0);
    }

    @Override
    public int getGlobalPoints(UUID uuid) {
        return getPoints(uuid, PointsTable.GLOBAL);
    }

    @Override
    public List<RankingPlayer> getTopGlobal(int limit) {
        return db.query(
                "SELECT uuid, player, global_points, season_points " +
                        "FROM " + table() + " " +
                        "ORDER BY global_points DESC, updated_at ASC LIMIT ?",
                rs -> new RankingPlayer(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("player"),
                        rs.getInt("global_points"),
                        rs.getInt("season_points")
                ),
                limit
        );
    }

    @Override
    public List<RankingPlayer> getTopSeason(int limit) {
        return db.query(
                "SELECT uuid, player, global_points, season_points " +
                        "FROM " + table() + " " +
                        "ORDER BY season_points DESC, updated_at ASC LIMIT ?",
                rs -> new RankingPlayer(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("player"),
                        rs.getInt("global_points"),
                        rs.getInt("season_points")
                ),
                limit
        );
    }

    @Override
    public void ensurePerformanceIndexes() {
        ensureIndex("idx_hexranking_player", "(`player`)");
        ensureIndex("idx_hexranking_top_global", "(`global_points` DESC, `updated_at` ASC)");
        ensureIndex("idx_hexranking_top_season", "(`season_points` DESC, `updated_at` ASC)");
    }

    private void ensureIndex(String indexName, String columnSpec) {
        try {
            db.update("CREATE INDEX `" + indexName + "` ON " + table() + " " + columnSpec);
        } catch (RuntimeException ex) {
            if (!isDuplicateIndex(ex)) {
                throw ex;
            }
        }
    }

    private static boolean isDuplicateIndex(Throwable ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("duplicate key name")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
