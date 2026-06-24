package hex.limbo.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import hex.limbo.config.PluginConfig;
import org.slf4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Owns the HikariCP connection pool to the MySQL database.
 */
public final class MySqlProvider {

    private final HikariDataSource dataSource;
    private final Logger logger;

    public MySqlProvider(PluginConfig.Database config, Logger logger) {
        this.logger = logger;
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8"
                        + "&serverTimezone=UTC"
                        + "&connectTimeout=%d"
                        + "&socketTimeout=%d"
                        + "&useSSL=%s"
                        + "&allowPublicKeyRetrieval=%s",
                config.host(),
                config.port(),
                config.database(),
                Math.max(1_000L, config.connectionTimeoutMs()),
                Math.max(1_000L, config.socketTimeoutMs()),
                config.useSsl(),
                config.allowPublicKeyRetrieval()
        ));
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(Math.max(1, config.poolSize()));
        hikari.setConnectionTimeout(Math.max(1_000L, config.connectionTimeoutMs()));
        hikari.setPoolName("HexLimbo-Hikari");
        hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
        this.dataSource = new HikariDataSource(hikari);
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public boolean ping() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (SQLException ex) {
            logger.error("Database ping failed", ex);
            return false;
        }
    }

    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
