package hex.core.api.db;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface Db {

    int update(String sql, Object... params);

    <T> List<T> query(String sql, RowMapper<T> mapper, Object... params);

    <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params);

    int[] batch(String sql, List<Object[]> batchParams);

    <T> T tx(Function<Db, T> work);

    String tablePrefix();

    default String t(String tableName) {
        return tablePrefix() + tableName;
    }
}
