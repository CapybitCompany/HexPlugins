package hexposterunki.persistence;

import hex.core.api.db.Db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Opens a database for repository tests.
 *
 * <p>By default this is H2 in MySQL compatibility mode, which runs the real repository statements
 * (transactions, {@code SELECT ... FOR UPDATE}, the revision comparisons) but is <b>not</b> MySQL.
 * Set {@code HEXPOSTERUNKI_TEST_MYSQL_URL} (plus optional {@code ..._USER} / {@code ..._PASSWORD})
 * to point the same tests at a real MySQL/MariaDB instance.
 */
public final class SqlTestSupport {

    public static final String MYSQL_URL_ENV = "HEXPOSTERUNKI_TEST_MYSQL_URL";

    private SqlTestSupport() {
    }

    public static boolean usingRealMysql() {
        String url = System.getenv(MYSQL_URL_ENV);
        return url != null && !url.isBlank();
    }

    public static Connection open() throws SQLException {
        String url = System.getenv(MYSQL_URL_ENV);
        if (url != null && !url.isBlank()) {
            return DriverManager.getConnection(url,
                    System.getenv(MYSQL_URL_ENV + "_USER"),
                    System.getenv(MYSQL_URL_ENV + "_PASSWORD"));
        }
        return DriverManager.getConnection(
                "jdbc:h2:mem:posterunki_" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "");
    }

    public static Db db(Connection connection) {
        return new JdbcTestDb(connection, "");
    }

    /** Fresh schema for one test. */
    public static PosterunkiRepository freshRepository(Connection connection) {
        PosterunkiRepository repository = new PosterunkiRepository(db(connection));
        repository.ensureTables();
        return repository;
    }
}
