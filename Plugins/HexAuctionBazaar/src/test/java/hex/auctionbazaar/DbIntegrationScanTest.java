package hex.auctionbazaar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Punkt #1: HexAuctionBazaar korzysta WYŁĄCZNIE z bazy HexCore.
 *  - żadnej własnej puli (HikariCP) ani sterownika JDBC,
 *  - żadnych danych dostępowych (jdbc:, host/user/password),
 *  - nigdy nie zamyka wspólnej puli HexCore (db().shutdown()).
 */
class DbIntegrationScanTest {

    private static final List<String> FORBIDDEN = List.of(
            "Hikari", "HikariDataSource", "HikariConfig",
            "java.sql.DriverManager", "DriverManager.getConnection",
            "jdbc:mysql", "jdbc:mariadb",
            "db().shutdown", "database().db().shutdown", "DatabaseService.shutdown");

    @Test
    void noOwnPoolNoJdbcNoHexCoreShutdown() throws IOException {
        Path root = Path.of("src/main/java");
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    for (String bad : FORBIDDEN) {
                        assertFalse(text.contains(bad),
                                "Plik " + p + " zawiera zabroniony fragment DB: " + bad);
                    }
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
    }

    @Test
    void configHasNoDbCredentials() throws IOException {
        String cfg = Files.readString(Path.of("src/main/resources/config.yml"), StandardCharsets.UTF_8);
        // Żadnych sekretów/danych połączenia w zasobach pluginu.
        for (String key : List.of("password:", "username:", "jdbc:", "\n  host:", "\n  port:")) {
            assertFalse(cfg.contains(key), "config.yml nie może zawierać danych połączenia: " + key);
        }
    }
}
