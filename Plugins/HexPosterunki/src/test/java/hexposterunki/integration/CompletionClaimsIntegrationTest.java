package hexposterunki.integration;

import hexposterunki.config.PosterunkiConfig;
import hexposterunki.config.RewardsConfig;
import hexposterunki.domain.KillEntry;
import hexposterunki.domain.RunPhase;
import hexposterunki.engine.EngineMode;
import hexposterunki.persistence.PosterunkiRepository;
import hexposterunki.persistence.RewardClaim;
import hexposterunki.rewards.FrozenRewards;
import hexposterunki.rewards.RewardPayload;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review finding 4: the frozen ranking and reward payloads of a victory are stored atomically with
 * the completed run, retried after a failure with exactly the frozen content, never duplicated, and
 * survive a restart.
 *
 * <p>The claim insert fails for real (the claims table is renamed inside H2), and every check reads
 * the database through the production repository SQL or plain SQL counts.
 */
class CompletionClaimsIntegrationTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** One kill suffices; place 1 earns a single item of the given material. */
    private static PosterunkiConfig rewarding(PosterunkiConfig base, String material) {
        PosterunkiConfig oneKill = new PosterunkiConfig(base.enabled(), base.debug(), base.timing(), 1,
                base.avoidImmediateRepeat(), base.waves(), base.boss(), base.towns(), base.protection(),
                base.ui(), base.rewards(), base.loot());
        return OutpostTestHarness.withRewards(oneKill, new RewardsConfig(true, 5, 3, Map.of(1,
                new RewardsConfig.PlaceReward(List.of(new RewardsConfig.ItemReward(material, 1, null, List.of())),
                        List.of()))));
    }

    /** Kills the whole wave with one winner; the next engine tick completes the run. */
    private Player clearTheWave(OutpostTestHarness harness) throws Exception {
        harness.setConfig(rewarding(harness.config(120L), "DIAMOND"));
        Player winner = harness.server.addPlayer("Zwyciezca");
        harness.towns.assign(winner.getUniqueId(), TOWN, "Rycerze");
        winner.teleport(harness.inside(8, 64, 8));
        harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test");
        harness.tick();
        harness.tick();
        harness.settle();
        assertEquals(RunPhase.WAVES, harness.engine.phase());

        List<LivingEntity> mobs = new ArrayList<>();
        for (Entity entity : harness.world.getEntities()) {
            if (harness.engine.liveMobs().containsKey(entity.getUniqueId())) {
                mobs.add((LivingEntity) entity);
            }
        }
        for (LivingEntity mob : mobs) {
            harness.engine.onRunMobDeath(mob, winner);
            mob.remove();
        }
        harness.settle();
        assertEquals(2, harness.engine.participation().ledger().kills(winner.getUniqueId()));
        return winner;
    }

    private static int count(OutpostTestHarness harness, String sql) throws Exception {
        try (Statement statement = harness.database().createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static List<RewardClaim> allClaims(OutpostTestHarness harness) {
        PosterunkiRepository repository = harness.persistence.repository();
        List<RewardClaim> claims = new ArrayList<>();
        for (RewardClaim.Status status : RewardClaim.Status.values()) {
            claims.addAll(repository.loadClaims(status));
        }
        return claims;
    }

    private static String material(RewardClaim claim) {
        return RewardPayload.deserialize(claim.payload()).components().get(0).value();
    }

    private void waitUntilUnhealthy(OutpostTestHarness harness) throws Exception {
        for (int i = 0; i < 400 && harness.persistence.healthy(); i++) {
            Thread.sleep(5L);
        }
        assertFalse(harness.persistence.healthy(), "zapis zakończenia musiał się nie udać");
    }

    @Test
    void theVictoryIsStoredTogetherWithItsClaimAndStandings() throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            Player winner = clearTheWave(harness);
            String runId = harness.engine.state().runId().orElseThrow();

            harness.engine.tick();                       // wave cleared -> COMPLETED
            assertEquals(RunPhase.COMPLETED, harness.engine.phase());
            harness.awaitWrites();

            PosterunkiRepository repository = harness.persistence.repository();
            assertEquals(RunPhase.COMPLETED, repository.loadAll().run().phase());
            List<RewardClaim> claims = allClaims(harness);
            assertEquals(1, claims.size());
            assertEquals(winner.getUniqueId(), claims.get(0).playerId());
            assertEquals(1, claims.get(0).place());
            assertEquals("DIAMOND", material(claims.get(0)));
            List<PosterunkiRepository.StatRow> stats = repository.loadCompletionStats(runId);
            assertEquals(1, stats.size());
            assertEquals(1, stats.get(0).place());
            assertEquals(2, stats.get(0).kills());

            harness.tick();                              // durable -> handed to the delivery
            harness.settle();
            harness.settle();
            assertEquals(RewardClaim.Status.DELIVERED, allClaims(harness).get(0).status());
            assertTrue(winner.getInventory().contains(Material.DIAMOND));
        }
    }

    @Test
    void aFailedCompletionWriteIsRetriedWithTheFrozenClaimAndNeverDuplicated() throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            Player winner = clearTheWave(harness);
            String runId = harness.engine.state().runId().orElseThrow();
            harness.sql("ALTER TABLE posterunki_reward_claims RENAME TO ukryte_roszczenia");

            harness.engine.tick();                       // COMPLETED, but the transaction fails
            waitUntilUnhealthy(harness);

            PosterunkiRepository repository = harness.persistence.repository();
            assertEquals(RunPhase.WAVES, repository.loadAll().run().phase(),
                    "bez zapisanych roszczeń nie ma zapisanego zakończenia");
            assertTrue(repository.loadCompletionStats(runId).isEmpty(), "transakcja wycofała także ranking");
            for (int i = 0; i < 5; i++) {
                harness.engine.tick();
            }
            assertEquals(RunPhase.COMPLETED, harness.engine.phase(), "bez trwałego zapisu nie ma fazy łupów");
            assertEquals(EngineMode.PERSISTENCE_PAUSED, harness.engine.mode());

            // The configuration changes meanwhile, and the freeze is requested again.
            PosterunkiConfig changed = rewarding(harness.config(120L), "DIRT");
            harness.setConfig(changed);
            FrozenRewards again = harness.rewards.freezeClaims(runId, "fort", TOWN,
                    List.of(new KillEntry(winner.getUniqueId(), TOWN, 2, 1L)), 1, changed.rewards());
            assertEquals(1, again.claims().size(), "nieudany zapis nie blokuje zamrożonych roszczeń");
            assertEquals("DIAMOND", material(again.claims().get(0)), "zamrożona treść nie zmienia się po edycji");

            harness.sql("ALTER TABLE ukryte_roszczenia RENAME TO posterunki_reward_claims");
            for (int i = 0; i < 200 && harness.engine.mode() != EngineMode.RUNNING; i++) {
                harness.engine.tick();
                Thread.sleep(5L);
            }
            harness.awaitWrites();
            for (int i = 0; i < 3; i++) {
                harness.engine.state().markChanged();
                harness.engine.persistState();
                harness.tick();
                harness.settle();
            }

            assertTrue(repository.loadAll().run().phase().isWon(), "zakończenie zapisane po odzyskaniu bazy");
            assertEquals(1, count(harness, "SELECT COUNT(*) FROM posterunki_reward_claims"),
                    "ponowienia nie mogą utworzyć drugiego roszczenia");
            assertEquals("DIAMOND", material(allClaims(harness).get(0)));
            assertEquals(1, repository.loadCompletionStats(runId).size());
        }
    }

    @Test
    void aCrashBeforeTheCompletionWasStoredLeavesNoOrphanedClaims() throws Exception {
        OutpostTestHarness harness = new OutpostTestHarness();
        String runId;
        try {
            clearTheWave(harness);
            runId = harness.engine.state().runId().orElseThrow();
            harness.sql("ALTER TABLE posterunki_reward_claims RENAME TO ukryte_roszczenia");
            harness.engine.tick();
            waitUntilUnhealthy(harness);
        } catch (Throwable failure) {
            harness.close();
            throw failure;
        }

        try (OutpostTestHarness restarted = harness.restart()) {
            restarted.recover();
            PosterunkiRepository repository = restarted.persistence.repository();

            assertEquals(RunPhase.WAVES, restarted.engine.phase(), "niezapisane zwycięstwo nie istnieje po restarcie");
            assertTrue(allClaims(restarted).isEmpty(), "brak osieroconych roszczeń");
            assertTrue(repository.loadCompletionStats(runId).isEmpty());
            assertFalse(restarted.rewards.ledger().isFrozen(runId));

            // The stored standings still hold the kills, so the cleared wave completes again - now durably.
            restarted.setConfig(rewarding(restarted.config(120L), "DIAMOND"));
            restarted.tick();
            restarted.settle();
            assertTrue(restarted.engine.phase().isWon());
            assertEquals(1, allClaims(restarted).size());
            assertEquals(RunPhase.COMPLETED, repository.loadAll().run().phase());
        }
    }

    @Test
    void aCrashAfterTheCompletionWasStoredKeepsTheClaimForTheReturningWinner() throws Exception {
        OutpostTestHarness harness = new OutpostTestHarness();
        UUID winnerId;
        try {
            winnerId = clearTheWave(harness).getUniqueId();
            harness.engine.tick();                       // COMPLETED together with the claim
            harness.awaitWrites();                       // no further engine tick: nothing delivered yet
            assertEquals(RewardClaim.Status.CLAIMED, allClaims(harness).get(0).status());
        } catch (Throwable failure) {
            harness.close();
            throw failure;
        }

        try (OutpostTestHarness restarted = harness.restart()) {
            restarted.recover();
            assertEquals(RunPhase.COMPLETED, restarted.engine.phase());
            assertEquals(1, allClaims(restarted).size());

            PlayerMock returning = new PlayerMock(restarted.server, "Zwyciezca", winnerId);
            restarted.server.addPlayer(returning);       // real PlayerJoinEvent -> delivery
            for (int i = 0; i < 5; i++) {
                restarted.settle();
            }

            List<RewardClaim> claims = allClaims(restarted);
            assertEquals(1, claims.size(), "restart nie tworzy drugiego roszczenia");
            assertEquals(RewardClaim.Status.DELIVERED, claims.get(0).status());
            assertTrue(returning.getInventory().contains(Material.DIAMOND));
        }
    }
}
