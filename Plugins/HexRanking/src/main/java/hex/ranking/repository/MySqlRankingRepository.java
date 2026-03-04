package hex.ranking.repository;

import hex.core.api.db.Db;
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
    public void addGlobalPoints(UUID uuid, int amount) {
        db.update(
                "INSERT INTO " + table() + " (uuid, global_points, season_points) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE " +
                        "global_points = GREATEST(0, global_points + VALUES(global_points)), " +
                        "season_points = GREATEST(0, season_points + VALUES(season_points))",
                uuid.toString(), amount, amount
        );
    }

    @Override
    public void removeGlobalPoints(UUID uuid, int amount) {
        db.update(
                "UPDATE " + table() + " SET " +
                        "global_points = GREATEST(0, global_points - ?), " +
                        "season_points = GREATEST(0, season_points - ?) " +
                        "WHERE uuid = ?",
                amount, amount, uuid.toString()
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
                "SELECT uuid, global_points, season_points " +
                        "FROM " + table() + " " +
                        "ORDER BY global_points DESC, updated_at ASC LIMIT ?",
                rs -> new RankingPlayer(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getInt("global_points"),
                        rs.getInt("season_points")
                ),
                limit
        );
    }
}
