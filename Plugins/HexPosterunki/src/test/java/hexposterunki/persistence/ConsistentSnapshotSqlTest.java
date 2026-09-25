package hexposterunki.persistence;

import hexposterunki.domain.BossStatus;
import hexposterunki.domain.KillEntry;
import hexposterunki.domain.RunPhase;
import hexposterunki.domain.RunSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 2 regression, executed against a real SQL engine (H2 in MySQL mode by default; point
 * {@code HEXPOSTERUNKI_TEST_MYSQL_URL} at MySQL to run the same tests there).
 *
 * <p>These drive the production {@link PosterunkiRepository} statements, not a reimplementation:
 * the transaction boundary, the {@code SELECT ... FOR UPDATE} on the pointer row, the revision
 * comparison and the revision-scoped replacement of participants and entities.
 */
class ConsistentSnapshotSqlTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private Connection connection;
    private PosterunkiRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connection = SqlTestSupport.open();
        repository = SqlTestSupport.freshRepository(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    private static RunSnapshot run(long revision, RunPhase phase, int wave, int mobsRemaining) {
        return new RunSnapshot("run-1", "fort", phase, wave, TOWN, "Rycerze", "golem", true,
                BossStatus.PENDING, null, 0, mobsRemaining, 1_000L, 2_000L, 0L, 3_000L, 0L, "", revision);
    }

    private static RunPersistenceSnapshot bundle(long revision, RunPhase phase, int wave,
                                                 List<KillEntry> participants,
                                                 List<TrackedEntity> entities) {
        return new RunPersistenceSnapshot(run(revision, phase, wave, entities.size()), "fort",
                participants, entities);
    }

    @Test
    void aSnapshotIsStoredCompletely() {
        repository.saveConsistent(bundle(5L, RunPhase.WAVES, 2,
                List.of(new KillEntry(PLAYER, TOWN, 7, 500L)),
                List.of(new TrackedEntity(UUID.randomUUID(), TrackedEntity.Kind.MOB, "zombie", 2))), 1L);

        PosterunkiRepository.LoadedState loaded = repository.loadAll();
        assertTrue(loaded.hasRun());
        assertTrue(loaded.consistent());
        assertEquals(RunPhase.WAVES, loaded.run().phase());
        assertEquals(2, loaded.run().wave());
        assertEquals(7, loaded.participants().get(0).kills());
        assertEquals(1, loaded.entities().size());
    }

    @Test
    void phaseTimersSurviveARestart() {
        RunSnapshot withTimers = new RunSnapshot("run-1", "fort", RunPhase.LOOTING, 3, TOWN, "Rycerze",
                "golem", true, BossStatus.DEAD, null, 1, 0, 1_000L, 2_000L, 0L,
                44_000L, 99_000L, "", 9L);
        repository.saveConsistent(new RunPersistenceSnapshot(withTimers, "fort", List.of(), List.of()), 1L);

        RunSnapshot loaded = repository.loadAll().run();
        assertEquals(44_000L, loaded.nextPhaseAtMillis(), "termin fazy musi przetrwać restart");
        assertEquals(99_000L, loaded.lootUntilMillis(), "koniec fazy łupów musi przetrwać restart");
        assertEquals(RunPhase.LOOTING, loaded.phase());
    }

    @Test
    void aLateOlderParticipantWriteCannotResurrectResetKills() {
        // Newer state first: the kill reset already landed.
        repository.saveConsistent(bundle(10L, RunPhase.WAVES, 2, List.of(), List.of()), 1L);

        // The delayed older write arrives afterwards with the pre-reset standings.
        SnapshotStore.WriteResult result = repository.saveConsistent(
                bundle(9L, RunPhase.WAVES, 2, List.of(new KillEntry(PLAYER, TOWN, 12, 100L)), List.of()), 2L);

        assertEquals(SnapshotStore.WriteResult.STALE, result);
        assertTrue(repository.loadAll().participants().isEmpty(),
                "spóźniony starszy zapis nie może przywrócić skasowanych zabójstw");
    }

    @Test
    void aLateOlderEntityWriteCannotResurrectRemovedEntities() {
        UUID mob = UUID.randomUUID();
        repository.saveConsistent(bundle(4L, RunPhase.WAVES, 1, List.of(),
                List.of(new TrackedEntity(mob, TrackedEntity.Kind.MOB, "zombie", 1))), 1L);
        // Wave cleared: the newer snapshot has no entities left.
        repository.saveConsistent(bundle(5L, RunPhase.WAVES, 1, List.of(), List.of()), 2L);

        SnapshotStore.WriteResult stale = repository.saveConsistent(bundle(4L, RunPhase.WAVES, 1, List.of(),
                List.of(new TrackedEntity(mob, TrackedEntity.Kind.MOB, "zombie", 1))), 3L);

        assertEquals(SnapshotStore.WriteResult.STALE, stale);
        assertTrue(repository.loadAll().entities().isEmpty(),
                "spóźniony starszy zapis nie może przywrócić usuniętych encji");
    }

    @Test
    void theSameRevisionIsNotWrittenTwice() {
        repository.saveConsistent(bundle(7L, RunPhase.WAVES, 1, List.of(), List.of()), 1L);
        assertEquals(SnapshotStore.WriteResult.STALE,
                repository.saveConsistent(bundle(7L, RunPhase.WAVES, 1, List.of(), List.of()), 2L));
    }

    @Test
    void aFailureInsideTheTransactionLeavesNoPartialState() {
        repository.saveConsistent(bundle(3L, RunPhase.WAVES, 1,
                List.of(new KillEntry(PLAYER, TOWN, 5, 100L)), List.of()), 1L);

        // An over-long mob id blows up the entity insert half-way through the transaction,
        // after the state, the run and the participants were already updated inside it.
        RunPersistenceSnapshot broken = new RunPersistenceSnapshot(
                run(4L, RunPhase.BOSS, 3, 0), "fort",
                List.of(new KillEntry(PLAYER, TOWN, 99, 900L)),
                List.of(new TrackedEntity(UUID.randomUUID(), TrackedEntity.Kind.MOB,
                        "x".repeat(400), 3)));

        assertThrows(RuntimeException.class, () -> repository.saveConsistent(broken, 2L));

        PosterunkiRepository.LoadedState loaded = repository.loadAll();
        assertEquals(RunPhase.WAVES, loaded.run().phase(), "faza nie mogła się zmienić");
        assertEquals(3L, loaded.run().revision());
        assertEquals(5, loaded.participants().get(0).kills(), "stary, spójny stan pozostaje");
    }

    @Test
    void aFailedSnapshotCanBeWrittenAgainWithTheSameRevision() {
        repository.saveConsistent(bundle(2L, RunPhase.WAVES, 1, List.of(), List.of()), 1L);

        RunPersistenceSnapshot broken = new RunPersistenceSnapshot(run(3L, RunPhase.BOSS, 2, 0), "fort",
                List.of(), List.of(new TrackedEntity(UUID.randomUUID(), TrackedEntity.Kind.MOB,
                "x".repeat(400), 2)));
        assertThrows(RuntimeException.class, () -> repository.saveConsistent(broken, 2L));

        // Same revision, this time healthy: the failed write did not consume the revision.
        SnapshotStore.WriteResult retried = repository.saveConsistent(
                bundle(3L, RunPhase.BOSS, 2, List.of(), List.of()), 3L);
        assertEquals(SnapshotStore.WriteResult.WRITTEN, retried);
        assertEquals(RunPhase.BOSS, repository.loadAll().run().phase());
    }

    private static CompletionRecord completion(String material, long completedAt) {
        String payload = hexposterunki.rewards.RewardPayload.from(new hexposterunki.config.RewardsConfig.PlaceReward(
                List.of(new hexposterunki.config.RewardsConfig.ItemReward(material, 1, null, List.of())),
                List.of())).serialize();
        return new CompletionRecord("run-1", "fort", completedAt,
                List.of(new PosterunkiRepository.StatRow(PLAYER, TOWN, 12, 1)),
                List.of(new RewardClaim("run-1", "fort", PLAYER, 1, RewardClaim.Status.CLAIMED, 0, 0, payload, null)));
    }

    private static RunPersistenceSnapshot completed(long revision, CompletionRecord completion) {
        return new RunPersistenceSnapshot(run(revision, RunPhase.COMPLETED, 3, 0), "fort",
                List.of(new KillEntry(PLAYER, TOWN, 12, 500L)), List.of(), completion);
    }

    @Test
    void theCompletionIsWrittenInTheSameTransactionAsTheCompletedRun() {
        repository.saveConsistent(completed(5L, completion("DIAMOND", 7_000L)), 1L);

        assertEquals(RunPhase.COMPLETED, repository.loadAll().run().phase());
        assertTrue(repository.loadClaim(RewardClaim.key("run-1", PLAYER, 1)).isPresent());
        List<PosterunkiRepository.StatRow> stats = repository.loadCompletionStats("run-1");
        assertEquals(1, stats.size());
        assertEquals(1, stats.get(0).place());
    }

    @Test
    void aFailureWhileStoringTheCompletionRollsBackTheCompletedRunAndTheClaims() throws Exception {
        repository.saveConsistent(bundle(4L, RunPhase.WAVES, 3, List.of(new KillEntry(PLAYER, TOWN, 12, 500L)),
                List.of()), 1L);
        // The claims insert succeeds inside the transaction; the standings insert right after it fails.
        try (java.sql.Statement sql = connection.createStatement()) {
            sql.execute("ALTER TABLE posterunki_stats RENAME TO ukryte_statystyki");
        }

        assertThrows(RuntimeException.class,
                () -> repository.saveConsistent(completed(5L, completion("DIAMOND", 7_000L)), 2L));

        try (java.sql.Statement sql = connection.createStatement()) {
            sql.execute("ALTER TABLE ukryte_statystyki RENAME TO posterunki_stats");
        }
        PosterunkiRepository.LoadedState loaded = repository.loadAll();
        assertEquals(RunPhase.WAVES, loaded.run().phase(), "zakończenie bez roszczeń nie może zostać zapisane");
        assertEquals(4L, loaded.run().revision());
        assertTrue(repository.loadClaim(RewardClaim.key("run-1", PLAYER, 1)).isEmpty(),
                "roszczenie bez zakończenia nie może zostać zapisane");

        // The very same snapshot succeeds once the database works again.
        assertEquals(SnapshotStore.WriteResult.WRITTEN,
                repository.saveConsistent(completed(5L, completion("DIAMOND", 7_000L)), 3L));
        assertTrue(repository.loadClaim(RewardClaim.key("run-1", PLAYER, 1)).isPresent());
    }

    @Test
    void carryingTheCompletionInLaterSnapshotsNeverDuplicatesOrChangesIt() {
        repository.saveConsistent(completed(5L, completion("DIAMOND", 7_000L)), 1L);
        repository.saveConsistent(completed(6L, completion("DIAMOND", 7_000L)), 2L);
        repository.saveConsistent(completed(7L, completion("DIRT", 9_000L)), 3L);

        assertEquals(1, repository.loadClaims(RewardClaim.Status.CLAIMED).size());
        String payload = repository.loadClaim(RewardClaim.key("run-1", PLAYER, 1)).orElseThrow().payload();
        assertEquals("DIAMOND", hexposterunki.rewards.RewardPayload.deserialize(payload).components().get(0).value(),
                "zapisane roszczenie nie zmienia się po późniejszym zapisie");
        assertEquals(1, repository.loadCompletionStats("run-1").size());
    }

    @Test
    void theResetCooldownSurvivesARestart() {
        RunSnapshot resetting = new RunSnapshot("run-1", "fort", RunPhase.RESETTING, 2, null, null, null, false,
                BossStatus.NONE, null, 0, 0, 1_000L, 0L, 0L, 0L, 0L, "", 8L, 45_000L);
        repository.saveConsistent(new RunPersistenceSnapshot(resetting, "fort", List.of(), List.of()), 1L);

        RunSnapshot loaded = repository.loadAll().run();
        assertEquals(RunPhase.RESETTING, loaded.phase());
        assertEquals(45_000L, loaded.resetCooldownMillis());
    }

    private static String definition(int minX) {
        return OutpostDefinitionCodec.encode(new hexposterunki.config.OutpostDefinition("fort", "Fort", "world",
                hexposterunki.config.Cuboid.of("world", new hexposterunki.config.BlockVec(minX, 60, 0),
                        new hexposterunki.config.BlockVec(minX + 31, 80, 31)),
                new hexposterunki.config.PointDef(minX + 16, 64, 16, 0, 0),
                new hexposterunki.config.PointDef(minX + 16, 64, 16, 0, 0),
                java.util.Map.of("brama", new hexposterunki.config.PointDef(minX + 4, 64, 4, 0, 0)),
                java.util.Map.of(), 1));
    }

    @Test
    void theRunsLocationIsStoredWithTheRunAndNeverErasedByALaterSnapshot() {
        String original = definition(0);
        repository.saveConsistent(new RunPersistenceSnapshot(run(5L, RunPhase.WAVES, 1, 0), "fort",
                List.of(), List.of(), null, original), 1L);
        assertEquals(original, repository.loadAll().outpostDefinition());

        // The cooldown holds no location and carries no definition; the stored one stays for this run.
        repository.saveConsistent(new RunPersistenceSnapshot(run(6L, RunPhase.COOLDOWN, 1, 0), "fort",
                List.of(), List.of(), null, null), 2L);
        assertEquals(original, repository.loadAll().outpostDefinition());

        // A stale snapshot carrying a different definition is rejected together with the run row.
        assertEquals(SnapshotStore.WriteResult.STALE, repository.saveConsistent(new RunPersistenceSnapshot(
                run(4L, RunPhase.WAVES, 1, 0), "fort", List.of(), List.of(), null, definition(300)), 3L));
        assertEquals(original, repository.loadAll().outpostDefinition());
    }

    @Test
    void anOlderRunsTableGainsTheDefinitionColumnAndItsRowsReportAnUnknownGeometry() throws Exception {
        repository.saveConsistent(bundle(5L, RunPhase.WAVES, 1, List.of(), List.of()), 1L);
        try (java.sql.Statement sql = connection.createStatement()) {
            sql.execute("ALTER TABLE posterunki_runs DROP COLUMN outpost_definition");
        }

        repository.ensureTables();   // the migration of an existing installation

        PosterunkiRepository.LoadedState loaded = repository.loadAll();
        assertTrue(loaded.hasRun());
        assertEquals(null, loaded.outpostDefinition(), "stary wiersz nie ma geometrii i nie udaje, że ją ma");
    }

    @Test
    void aPointerThatDisagreesWithTheRunIsReportedAsInconsistent() {
        repository.saveConsistent(bundle(6L, RunPhase.WAVES, 1, List.of(), List.of()), 1L);
        // Simulate damage from an older build: bump the pointer revision only.
        SqlTestSupport.db(connection).update("UPDATE posterunki_state SET revision = 42 WHERE id = 1");

        PosterunkiRepository.LoadedState loaded = repository.loadAll();
        assertFalse(loaded.consistent());
        assertFalse(loaded.hasRun(), "niespójny stan nie może zostać wznowiony");
    }
}
