package hex.statsapi.repository;

import hex.statsapi.config.StatDefinition;
import hex.statsapi.dto.StatResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class StatRepository {

    private final JdbcTemplate jdbc;

    public StatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<StatResponse.PlayerStatEntry> fetchLeaderboard(StatDefinition definition, int limit) {
        String sql = String.format(
                "SELECT %s AS uuid, %s AS nickname, %s AS value FROM %s ORDER BY %s %s LIMIT ?",
                definition.getUuidColumn(),
                definition.getNicknameColumn(),
                definition.getValueColumn(),
                definition.getTable(),
                definition.getValueColumn(),
                definition.getOrder()
        );

        return jdbc.query(sql, (rs, rowNum) -> new StatResponse.PlayerStatEntry(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("nickname"),
                rs.getLong("value")
        ), limit);
    }

    public StatResponse.PlayerStatEntry fetchForPlayer(StatDefinition definition, UUID uuid) {
        String sql = String.format(
                "SELECT %s AS uuid, %s AS nickname, %s AS value FROM %s WHERE %s = ?",
                definition.getUuidColumn(),
                definition.getNicknameColumn(),
                definition.getValueColumn(),
                definition.getTable(),
                definition.getUuidColumn()
        );

        return jdbc.queryForObject(sql, (rs, rowNum) -> new StatResponse.PlayerStatEntry(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("nickname"),
                rs.getLong("value")
        ), uuid.toString());
    }
}

