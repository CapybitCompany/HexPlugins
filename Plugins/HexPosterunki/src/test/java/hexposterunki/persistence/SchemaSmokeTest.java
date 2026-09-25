package hexposterunki.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that the production DDL and the idempotent migration actually run. */
class SchemaSmokeTest {

    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = SqlTestSupport.open();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void schemaCreationIsIdempotent() {
        PosterunkiRepository repository = new PosterunkiRepository(SqlTestSupport.db(connection));
        assertDoesNotThrow(repository::ensureTables);
        // Running it again must not fail, and must not duplicate the migrated columns.
        assertDoesNotThrow(repository::ensureTables);
        assertDoesNotThrow(repository::ensureTables);
    }

    @Test
    void migrationAddsColumnsToAPreExistingOlderTable() {
        // Simulate the first release: participants without the revision column.
        SqlTestSupport.db(connection).update("CREATE TABLE posterunki_participants ("
                + "run_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL, town_id VARCHAR(36),"
                + "kills INT NOT NULL DEFAULT 0, achieved_at BIGINT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (run_id, player_uuid))");
        SqlTestSupport.db(connection).update(
                "INSERT INTO posterunki_participants (run_id, player_uuid, kills, achieved_at)"
                        + " VALUES ('run-old','00000000-0000-0000-0000-0000000000a1', 4, 10)");

        PosterunkiRepository repository = new PosterunkiRepository(SqlTestSupport.db(connection));
        repository.ensureTables();

        boolean hasRevision = SqlTestSupport.db(connection).queryOne(
                "SELECT revision FROM posterunki_participants WHERE run_id = 'run-old'",
                rs -> rs.getLong("revision") == 0L).orElse(false);
        assertTrue(hasRevision, "istniejące wiersze dostają revision = 0 i pozostają czytelne");
    }

    // ---------------------------------------------------------------- several schemas on one server

    private void sql(String statement) throws Exception {
        try (java.sql.Statement sql = connection.createStatement()) {
            sql.execute(statement);
        }
    }

    private boolean columnReadable(String table, String column) {
        try (java.sql.Statement sql = connection.createStatement()) {
            sql.executeQuery("SELECT " + column + " FROM " + table + " WHERE 1 = 0").close();
            return true;
        } catch (java.sql.SQLException missing) {
            return false;
        }
    }

    /**
     * Another server's tables in a second schema, one with the column and one without, and this
     * server's older table with existing data. Uses H2's {@code SET SCHEMA}; the production query relies
     * on {@code SCHEMA()}, which MySQL and MariaDB define as the current database.
     */
    private void migrationIsScopedToTheCurrentSchema(String prefix) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(SqlTestSupport.usingRealMysql(),
                "scenariusz dwóch schematów używa składni H2 (SET SCHEMA)");
        String runs = prefix + "posterunki_runs";
        String participants = prefix + "posterunki_participants";
        PosterunkiRepository current = new PosterunkiRepository(new JdbcTestDb(connection, prefix));

        sql("CREATE SCHEMA inny_serwer");
        sql("SET SCHEMA inny_serwer");
        current.ensureTables();                                             // full tables over there
        sql("ALTER TABLE " + participants + " DROP COLUMN revision");      // ... except this column
        sql("SET SCHEMA public");

        current.ensureTables();
        current.saveConsistent(new RunPersistenceSnapshot(new hexposterunki.domain.RunSnapshot("run-1", "fort",
                hexposterunki.domain.RunPhase.WAVES, 2, null, null, null, false,
                hexposterunki.domain.BossStatus.NONE, null, 0, 0, 1L, 0L, 0L, 0L, 0L, "", 7L),
                "fort", java.util.List.of(), java.util.List.of()), 1L);
        sql("ALTER TABLE " + runs + " DROP COLUMN outpost_definition");   // this server: older table
        assertTrue(columnReadable("inny_serwer." + runs, "outpost_definition"));

        current.ensureTables();
        current.ensureTables();                                             // repeated migration

        assertTrue(columnReadable(runs, "outpost_definition"),
                "kolumna innego schematu nie może zablokować migracji w bieżącym schemacie");
        PosterunkiRepository.LoadedState loaded = current.loadAll();
        assertTrue(loaded.hasRun(), "istniejące dane przetrwały migrację");
        assertEquals(7L, loaded.run().revision());
        assertEquals(null, loaded.outpostDefinition());
        assertTrue(columnReadable(participants, "revision"));
        assertFalse(columnReadable("inny_serwer." + participants, "revision"),
                "migracja nie zmienia tabel innego schematu");
    }

    @Test
    void migrationOnlyInspectsTheCurrentSchema() throws Exception {
        migrationIsScopedToTheCurrentSchema("");
    }

    @Test
    void migrationOnlyInspectsTheCurrentSchemaWithATablePrefix() throws Exception {
        migrationIsScopedToTheCurrentSchema("hex_");
    }
}
