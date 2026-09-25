package hexposterunki.integration;

import hexposterunki.domain.RunPhase;
import hexposterunki.engine.EngineMode;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review round 2, finding 3: the cleanup of a technically failed run continues after a real SQL
 * read failure without another admin command - through the engine tick, through the scheduled retry
 * when no tick runs, and after a restart.
 *
 * <p>The read failure is real: the block-state table is renamed inside H2. The engine has no plugin
 * here, so nothing re-enables operation - that is the plugin's responsibility (see the lifecycle
 * test), and the checks below confirm the engine does not do it on its own.
 */
class ResetRetryIntegrationTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static void startFight(OutpostTestHarness harness) throws Exception {
        Player player = harness.server.addPlayer("Obronca");
        harness.towns.assign(player.getUniqueId(), TOWN, "Rycerze");
        player.teleport(harness.inside(8, 64, 8));
        assertTrue(harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test"));
        harness.tick();
        harness.tick();
        harness.settle();
        assertEquals(RunPhase.WAVES, harness.engine.phase());
    }

    private static boolean breakIsCancelled(OutpostTestHarness harness) {
        Block block = harness.world.getBlockAt(8, 64, 9);
        block.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(block, harness.server.addPlayer());
        harness.server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    /** Fails the run, hides the block states and lets the admin reset hit the read failure. */
    private static void failedRunWhoseCleanupCannotReadItsBlocks(OutpostTestHarness harness) throws Exception {
        startFight(harness);
        assertTrue(harness.ticketWorld.ticketCount() > 0);
        harness.engine.failRun("symulowany błąd techniczny");
        harness.awaitWrites();
        assertFalse(harness.engine.operational());
        assertEquals(EngineMode.STOPPED, harness.engine.mode());

        harness.sql("ALTER TABLE posterunki_blocks RENAME TO ukryte_bloki");
        assertTrue(harness.engine.adminReset(null));
        harness.awaitWrites();
        for (int i = 0; i < 100 && harness.engine.resetInProgress(); i++) {
            harness.server.getScheduler().performOneTick();
            Thread.sleep(5L);
        }
        assertFalse(harness.engine.resetInProgress(), "odczyt bloków musiał się nie udać");
        assertInterruptedCleanup(harness);
    }

    /** The cleanup is interrupted, but nothing of it is lost and nothing else may happen meanwhile. */
    private static void assertInterruptedCleanup(OutpostTestHarness harness) {
        assertEquals(RunPhase.RESETTING, harness.engine.phase());
        assertEquals(0L, harness.engine.state().resetCooldownMillis(), "zamiar resetu (bez przerwy) zostaje");
        assertEquals(EngineMode.RESETTING, harness.engine.mode());
        assertEquals(RunPhase.RESETTING, harness.persistence.repository().loadAll().run().phase());
        assertTrue(breakIsCancelled(harness), "teren pozostaje chroniony do końca resetu");
        assertTrue(harness.ticketWorld.ticketCount() > 0, "tickety zostają do zakończenia resetu");
        assertFalse(harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test"),
                "podczas resetu nie startuje nowa runda");
    }

    private static void assertFinished(OutpostTestHarness harness) throws Exception {
        assertEquals(RunPhase.COOLDOWN, harness.engine.phase());
        assertTrue(harness.engine.state().cooldownUntilMillis() <= System.currentTimeMillis() + 1_000L,
                "reset administratora kończy się bez przerwy");
        assertEquals(0, harness.ticketWorld.ticketCount(), "tickety zostały zwolnione");
        assertTrue(harness.regions.active().isEmpty());
        assertFalse(harness.engine.operational(), "silnik sam nie przywraca pracy po nieudanym runie");
        assertEquals(EngineMode.STOPPED, harness.engine.mode());
        harness.awaitWrites();
        assertEquals(RunPhase.COOLDOWN, harness.persistence.loadAll().get().run().phase());
    }

    @Test
    void theEngineTickRetriesTheCleanupOfAFailedRunOnceTheReadWorksAgain() throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            failedRunWhoseCleanupCannotReadItsBlocks(harness);

            for (int i = 0; i < 5; i++) {
                harness.tick();          // retries fail while the table is still missing
                Thread.sleep(5L);
            }
            for (int i = 0; i < 100 && harness.engine.resetInProgress(); i++) {
                harness.server.getScheduler().performOneTick();
                Thread.sleep(5L);
            }
            assertInterruptedCleanup(harness);

            harness.sql("ALTER TABLE ukryte_bloki RENAME TO posterunki_blocks");
            for (int i = 0; i < 100 && harness.engine.phase() == RunPhase.RESETTING; i++) {
                harness.tick();
                Thread.sleep(5L);
            }
            harness.settle();
            assertFinished(harness);
        }
    }

    @Test
    void theScheduledRetryFinishesTheCleanupWithoutAnyEngineTick() throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            failedRunWhoseCleanupCannotReadItsBlocks(harness);
            harness.sql("ALTER TABLE ukryte_bloki RENAME TO posterunki_blocks");

            // No engine.tick(): only the Bukkit scheduler runs, as with a stopped event scheduler.
            for (int i = 0; i < 200 && harness.engine.phase() == RunPhase.RESETTING; i++) {
                harness.server.getScheduler().performOneTick();
                Thread.sleep(2L);
            }
            harness.settle();
            assertFinished(harness);
        }
    }

    @Test
    void aRestartAfterTheReadFailureFinishesTheSameCleanup() throws Exception {
        OutpostTestHarness harness = new OutpostTestHarness();
        try {
            failedRunWhoseCleanupCannotReadItsBlocks(harness);
            harness.sql("ALTER TABLE ukryte_bloki RENAME TO posterunki_blocks");   // before any retry ran
        } catch (Throwable failure) {
            harness.close();
            throw failure;
        }

        try (OutpostTestHarness restarted = harness.restart()) {
            restarted.recover();
            assertEquals(RunPhase.RESETTING, restarted.engine.phase(), "po restarcie reset jest kontynuowany");
            assertEquals(EngineMode.RESETTING, restarted.engine.mode());
            assertTrue(breakIsCancelled(restarted));
            assertTrue(restarted.ticketWorld.ticketCount() > 0);

            for (int i = 0; i < 200 && restarted.engine.phase() == RunPhase.RESETTING; i++) {
                restarted.server.getScheduler().performOneTick();
                Thread.sleep(5L);
            }
            restarted.settle();
            assertEquals(RunPhase.COOLDOWN, restarted.engine.phase());
            assertTrue(restarted.engine.state().cooldownUntilMillis() <= System.currentTimeMillis() + 1_000L,
                    "zapisany zamiar (bez przerwy) przetrwał restart");
            assertEquals(0, restarted.ticketWorld.ticketCount());
            assertTrue(restarted.regions.active().isEmpty());
            restarted.awaitWrites();
            assertEquals(RunPhase.COOLDOWN, restarted.persistence.loadAll().get().run().phase());
        }
    }
}
