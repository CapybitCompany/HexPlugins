package hexposterunki.integration;

import hexposterunki.config.LootConfig;
import hexposterunki.config.PosterunkiConfig;
import hexposterunki.domain.RunPhase;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full review, finding 3: a container fill whose database claim failed is retried until it succeeds,
 * also after a restart, and never fills a container twice. The claim fails for real: the
 * {@code posterunki_loot} table is renamed inside H2 while the state writer keeps working.
 */
class LootFillRetryIntegrationTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static PosterunkiConfig completionLoot(PosterunkiConfig c) {
        LootConfig loot = new LootConfig(true, LootConfig.FillPhase.COMPLETED, LootConfig.ResetMode.CLEAR,
                "standard", null, Map.of("standard", new LootConfig.LootTable(1,
                List.of(new LootConfig.LootEntry("DIAMOND", 1, 1, 1)))), Map.of());
        return new PosterunkiConfig(c.enabled(), c.debug(), c.timing(), c.requiredKills(), c.avoidImmediateRepeat(),
                c.waves(), c.boss(), c.towns(), c.protection(), c.ui(), c.rewards(), loot);
    }

    private static Chest chest(OutpostTestHarness harness) {
        return (Chest) harness.world.getBlockAt(10, 64, 10).getState();
    }

    private static boolean filled(OutpostTestHarness harness) {
        return chest(harness).getInventory().contains(Material.DIAMOND);
    }

    private static int storedClaims(OutpostTestHarness harness) throws Exception {
        try (Statement statement = harness.database().createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM posterunki_loot")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** Wins the wave while the loot claim table is hidden; the state writer stays healthy. */
    private static void winWhileTheLootClaimFails(OutpostTestHarness harness) throws Exception {
        harness.setConfig(completionLoot(harness.currentConfig()));
        harness.world.getBlockAt(10, 64, 10).setType(Material.CHEST);
        Player player = harness.server.addPlayer("Obronca");
        harness.towns.assign(player.getUniqueId(), TOWN, "Rycerze");
        player.teleport(harness.inside(8, 64, 8));
        assertTrue(harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test"));
        harness.tick();
        harness.tick();
        harness.settle();
        assertEquals(RunPhase.WAVES, harness.engine.phase());

        harness.sql("ALTER TABLE posterunki_loot RENAME TO ukryty_loot");
        for (Entity entity : harness.world.getEntities()) {
            if (harness.engine.liveMobs().containsKey(entity.getUniqueId())) {
                harness.engine.onRunMobDeath((LivingEntity) entity, player);
                entity.remove();
            }
        }
        harness.tick();          // COMPLETED: the claim is attempted and fails
        harness.settle();
        assertEquals(RunPhase.COMPLETED, harness.engine.phase());
        assertTrue(harness.persistence.healthy(), "zapis stanu działa - zawiodła tylko rejestracja łupów");
        assertFalse(filled(harness));
        assertFalse(harness.loot.isUnlocked("fort", "skrzynia"));
    }

    private static void tickRunning(OutpostTestHarness harness, int ticks) throws Exception {
        for (int i = 0; i < ticks; i++) {
            harness.tick();
            Thread.sleep(5L);
        }
        harness.settle();
    }

    @Test
    void aFailedClaimIsRetriedOnceTheDatabaseWorksAndFillsExactlyOnce() throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            winWhileTheLootClaimFails(harness);
            tickRunning(harness, 5);      // retries keep failing while the table is missing
            assertFalse(filled(harness));
            assertEquals(RunPhase.LOOTING, harness.engine.phase(), "faza łupów trwa mimo nieudanej rejestracji");

            harness.sql("ALTER TABLE ukryty_loot RENAME TO posterunki_loot");
            tickRunning(harness, 20);

            assertTrue(filled(harness), "skrzynia zwycięzców zostaje napełniona po naprawie bazy");
            assertTrue(harness.loot.isUnlocked("fort", "skrzynia"));
            assertEquals(1, storedClaims(harness));

            // Looted - and then the fill is requested again, as a recovery without knowledge would do.
            chest(harness).getInventory().clear();
            harness.engine.resumeDueLootFills(List.of());
            tickRunning(harness, 10);
            assertFalse(filled(harness), "opróżniona skrzynia nie jest napełniana ponownie");
            assertEquals(1, storedClaims(harness));
        }
    }

    @Test
    void aRestartAfterTheFailedClaimFillsTheContainerOnceAndNotAgainOnTheNextRestart() throws Exception {
        OutpostTestHarness harness = new OutpostTestHarness();
        try {
            winWhileTheLootClaimFails(harness);
            harness.sql("ALTER TABLE ukryty_loot RENAME TO posterunki_loot");   // repaired, but no retry ran yet
            harness.awaitWrites();
        } catch (Throwable failure) {
            harness.close();
            throw failure;
        }

        OutpostTestHarness restarted = harness.restart();
        try {
            restarted.setConfig(completionLoot(restarted.currentConfig()));
            restarted.world.getBlockAt(10, 64, 10).setType(Material.CHEST);
            restarted.recover();
            tickRunning(restarted, 10);

            assertTrue(filled(restarted), "po restarcie zaległe napełnienie zostaje wykonane");
            assertEquals(1, storedClaims(restarted));
        } catch (Throwable failure) {
            restarted.close();
            throw failure;
        }

        try (OutpostTestHarness again = restarted.restart()) {
            again.setConfig(completionLoot(again.currentConfig()));
            again.world.getBlockAt(10, 64, 10).setType(Material.CHEST);   // empty chest in the new world
            again.recover();
            tickRunning(again, 10);

            assertFalse(filled(again), "zarejestrowane napełnienie nie jest powtarzane");
            assertTrue(again.loot.isUnlocked("fort", "skrzynia"));
            assertEquals(1, storedClaims(again));
        }
    }

    @Test
    void aPendingFillIsDroppedWhenTheRunIsResetMeanwhile() throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            winWhileTheLootClaimFails(harness);
            String runId = harness.engine.state().runId().orElseThrow();

            assertTrue(harness.engine.adminStop());
            harness.sql("ALTER TABLE ukryty_loot RENAME TO posterunki_loot");
            for (int i = 0; i < 200 && harness.engine.phase() == RunPhase.RESETTING; i++) {
                harness.server.getScheduler().performOneTick();
                Thread.sleep(5L);
            }
            tickRunning(harness, 10);

            assertEquals(RunPhase.COOLDOWN, harness.engine.phase());
            assertFalse(filled(harness), "zakończony run nie dostaje łupów");
            assertFalse(harness.loot.isUnlocked("fort", "skrzynia"));
            assertEquals(0, storedClaims(harness), "brak rejestracji dla zakończonego runu " + runId);
        }
    }

    @Test
    void aClaimAnsweredAfterTheResetStartedDoesNotFillTheChest() throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            winWhileTheLootClaimFails(harness);
            harness.sql("ALTER TABLE ukryty_loot RENAME TO posterunki_loot");

            Runnable release = harness.holdDatabase();
            try {
                harness.engine.tick();                 // retry claim queued behind the held database
                assertTrue(harness.engine.adminStop()); // reset starts while the claim is in flight
            } finally {
                release.run();
            }
            for (int i = 0; i < 200 && harness.engine.phase() == RunPhase.RESETTING; i++) {
                harness.server.getScheduler().performOneTick();
                Thread.sleep(5L);
            }
            harness.settle();

            assertEquals(RunPhase.COOLDOWN, harness.engine.phase());
            assertFalse(filled(harness), "spóźniona odpowiedź bazy nie napełnia skrzyni po resecie");
            assertFalse(harness.loot.isUnlocked("fort", "skrzynia"));
        }
    }

    @Test
    void aClaimOfUnclearOutcomeUnlocksTheContainerButNeverFillsIt() throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            winWhileTheLootClaimFails(harness);
            String runId = harness.engine.state().runId().orElseThrow();
            // The failed attempt may have been committed after all: the claim row exists.
            harness.sql("ALTER TABLE ukryty_loot RENAME TO posterunki_loot");
            harness.sql("INSERT INTO posterunki_loot (run_id, container_id, filled_at) VALUES ('" + runId
                    + "', 'fort:skrzynia', 1)");

            tickRunning(harness, 10);

            assertFalse(filled(harness), "niepewny stan nie prowadzi do ponownego napełnienia");
            assertTrue(harness.loot.isUnlocked("fort", "skrzynia"));
            boolean audited = false;
            for (int i = 0; i < 200 && !audited; i++) {
                audited = harness.persistence.repository().recentAudit(50).stream()
                        .anyMatch(line -> line.contains("LOOT_UNCERTAIN"));
                Thread.sleep(5L);
            }
            assertTrue(audited, "niepewny wynik jest odnotowany w audycie");
        }
    }
}
