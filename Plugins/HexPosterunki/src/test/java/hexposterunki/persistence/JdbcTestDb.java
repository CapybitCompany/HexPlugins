package hexposterunki.persistence;

import hex.core.api.db.Db;
import hex.core.api.db.RowMapper;
import hex.core.api.db.SqlException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Test-only JDBC adapter for HexCore's {@link Db} contract.
 *
 * <p>It exists so the <b>real</b> {@link PosterunkiRepository} SQL - including its transaction
 * boundaries, row locks and revision comparisons - runs against a real database engine in tests.
 * It does not reimplement any plugin logic; it only maps the seven {@code Db} methods onto JDBC,
 * mirroring what {@code HikariDatabaseService.DbImpl} does in production.
 */
public final class JdbcTestDb implements Db {

    private final Connection connection;
    private final String prefix;
    private final boolean transactional;

    public JdbcTestDb(Connection connection, String prefix) {
        this(connection, prefix, false);
    }

    private JdbcTestDb(Connection connection, String prefix, boolean transactional) {
        this.connection = connection;
        this.prefix = prefix == null ? "" : prefix;
        this.transactional = transactional;
    }

    @Override
    public String tablePrefix() {
        return prefix;
    }

    @Override
    public int update(String sql, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new SqlException("update failed: " + sql, exception);
        }
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        } catch (SQLException exception) {
            throw new SqlException("query failed: " + sql, exception);
        }
    }

    @Override
    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> list = query(sql, mapper, params);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public int[] batch(String sql, List<Object[]> batchParams) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Object[] params : batchParams) {
                bind(statement, params);
                statement.addBatch();
            }
            return statement.executeBatch();
        } catch (SQLException exception) {
            throw new SqlException("batch failed: " + sql, exception);
        }
    }

    @Override
    public <T> T tx(Function<Db, T> work) {
        if (transactional) {
            // Already inside a transaction: reuse it instead of nesting.
            return work.apply(this);
        }
        try {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.apply(new JdbcTestDb(connection, prefix, true));
                connection.commit();
                return result;
            } catch (RuntimeException | Error error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new SqlException("tx failed", exception);
        }
    }

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }
}
