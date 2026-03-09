package hex.ranking.repository;

import hex.core.api.db.Db;
import hex.ranking.model.PointsTable;
import hex.ranking.model.RankingPlayer;

import java.util.List;
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
    public void addPoints(UUID uuid, PointsTable pointsTable, int amount) {
        String pointsColumn = pointsTable.column();
        int globalDelta = pointsTable == PointsTable.GLOBAL ? amount : 0;
        int seasonDelta = pointsTable == PointsTable.SEASON ? amount : 0;

        db.update(
                "INSERT INTO " + table() + " (uuid, global_points, season_points) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE " +
                        pointsColumn + " = GREATEST(0, " + pointsColumn + " + VALUES(" + pointsColumn + "))",
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
    public int getGlobalPoints(UUID uuid) {
        return db.queryOne(
                "SELECT global_points FROM " + table() + " WHERE uuid=?",
                rs -> rs.getInt("global_points"),
                uuid.toString()
        ).orElse(0);
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
}
