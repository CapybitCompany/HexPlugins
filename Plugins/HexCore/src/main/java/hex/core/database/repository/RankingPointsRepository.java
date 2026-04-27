package hex.core.database.repository;

import hex.core.database.model.RankingPointsRecord;
import hex.core.database.model.RankingTopEntry;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class RankingPointsRepository {

    private final DataSource dataSource;
    private final String tableName;

    public RankingPointsRepository(DataSource dataSource, String tablePrefix) {
        this.dataSource = dataSource;
        this.tableName = (tablePrefix == null ? "" : tablePrefix) + "ranking_points";
    }

    public Optional<RankingPointsRecord> findByUuid(UUID uuid) {
        String sql = "SELECT uuid, global_points, season_points, updated_at FROM " + tableName + " WHERE uuid = ?";

        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }

                return Optional.of(new RankingPointsRecord(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getInt("global_points"),
                        rs.getInt("season_points"),
                        mapInstant(rs)
                ));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load ranking points for uuid=" + uuid, exception);
        }
    }

    /**
     * Returns TOP N players for global ranking.
     */
    public List<RankingTopEntry> findTopGlobal(int limit) {
        return findTop("global_points", limit);
    }

    /**
     * Returns TOP N players for season ranking.
     */
    public List<RankingTopEntry> findTopSeason(int limit) {
        return findTop("season_points", limit);
    }

    private List<RankingTopEntry> findTop(String pointsColumn, int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));

        // NOTE: We try to fetch player name from xeconomy if available.
        // If you store names elsewhere, adjust the join.
        String sql = "SELECT rp.uuid AS uuid, rp." + pointsColumn + " AS points, xe.player AS player " +
                "FROM " + tableName + " rp " +
                "LEFT JOIN xconomy xe ON xe.UID = rp.uuid " +
                "ORDER BY rp." + pointsColumn + " DESC " +
                "LIMIT " + safeLimit;

        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {

            List<RankingTopEntry> out = new ArrayList<>(safeLimit);
            while (rs.next()) {
                String uuidStr = rs.getString("uuid");
                UUID uuid = uuidStr == null ? null : UUID.fromString(uuidStr);
                String name = rs.getString("player");
                int points = rs.getInt("points");
                if (uuid != null) {
                    out.add(new RankingTopEntry(uuid, name, points));
                }
            }
            return out;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load top ranking for column=" + pointsColumn, exception);
        }
    }

    public int findGlobalRankPosition(UUID uuid) {
        return findRankPosition("global_points", uuid);
    }

    public int findSeasonRankPosition(UUID uuid) {
        return findRankPosition("season_points", uuid);
    }

    private int findRankPosition(String pointsColumn, UUID uuid) {
        if (uuid == null) {
            return -1;
        }

        // Return -1 when player is not present in ranking_points.
        // Otherwise compute 1-based rank by counting players with strictly higher score.
        String sql = "SELECT CASE " +
                "WHEN EXISTS (SELECT 1 FROM " + tableName + " WHERE uuid = ?) " +
                "THEN 1 + (SELECT COUNT(*) FROM " + tableName + " WHERE " + pointsColumn +
                " > (SELECT " + pointsColumn + " FROM " + tableName + " WHERE uuid = ?)) " +
                "ELSE -1 END AS pos";

        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, uuid.toString());
            statement.setString(2, uuid.toString());

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return -1;
                }
                return rs.getInt("pos");
            }
        } catch (SQLException exception) {
            return -1;
        }
    }

    private Instant mapInstant(ResultSet rs) throws SQLException {
        var timestamp = rs.getTimestamp("updated_at");
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }
}
