package hex.core.database.repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only repository for coins/balance stored in external table (xeconomy).
 *
 * Table (external): xeconomy(uuid, player, balance, hidden)
 */
public final class CoinsRepository {

    private final DataSource dataSource;
    private final String tableName;

    public CoinsRepository(DataSource dataSource, String tablePrefix) {
        this.dataSource = dataSource;
        // Zakładamy, że to tabela zewnętrznego pluginu - zwykle bez prefixu.
        // Jeśli jednak macie prefix w DB, można go włączyć.
        this.tableName = (tablePrefix == null ? "" : tablePrefix) + "xconomy";
    }

    public Optional<Integer> findBalanceByUuid(UUID uuid) {
        String sql = "SELECT balance FROM " + tableName + " WHERE UID = ?";

        try (var connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }

                return Optional.of(rs.getInt("balance"));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load coins balance for uuid=" + uuid, exception);
        }
    }
}

