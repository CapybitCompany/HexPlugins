package hexposterunki.integration;

import hexposterunki.config.BlockVec;
import hexposterunki.config.Cuboid;
import hexposterunki.config.OutpostCatalog;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.PointDef;
import hexposterunki.config.ValidationReport;
import hexposterunki.domain.RunPhase;
import hexposterunki.engine.EngineMode;
import hexposterunki.persistence.OutpostDefinitionCodec;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review round 2, finding 1: the geometry a run was started with survives a reload followed by a
 * restart. The outpost is moved or removed by a reload, the server restarts during WAVES or during
 * RESETTING, and recovery must keep fighting, protecting and cleaning up at the original location.
 *
 * <p>Protection is judged by the registered {@code RegionProtectionListener}; the cleanup is
 * observed through fire blocks at the original and at the moved position (the fire sweep scans the
 * run's region only) and through the chunk tickets of the run.
 */
class FrozenGeometryRestartIntegrationTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private enum Reload { MOVED, REMOVED }

    private static OutpostDefinition moved() {
        return new OutpostDefinition("fort", "Fort Przeniesiony", OutpostTestHarness.WORLD,
                Cuboid.of(OutpostTestHarness.WORLD, new BlockVec(300, 60, 300), new BlockVec(331, 80, 331)),
                new PointDef(316, 64, 316, 0, 0), new PointDef(316, 64, 316, 0, 0),
                Map.of("brama", new PointDef(304, 64, 304, 0, 0)),
                Map.of("skrzynia", new BlockVec(310, 64, 310)), 10);
    }

    private static OutpostCatalog catalogAfter(Reload reload) {
        return reload == Reload.MOVED
                ? new OutpostCatalog(Map.of("fort", moved()), new ValidationReport())
                : OutpostCatalog.empty();
    }

    private String startFight(OutpostTestHarness harness) throws Exception {
        Player player = harness.server.addPlayer("Obronca");
        harness.towns.assign(player.getUniqueId(), TOWN, "Rycerze");
        player.teleport(harness.inside(8, 64, 8));
        assertTrue(harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test"));
        harness.tick();
        harness.tick();
        harness.settle();
        assertEquals(RunPhase.WAVES, harness.engine.phase());
        return harness.engine.state().runId().orElseThrow();
    }

    private static boolean breakIsCancelled(OutpostTestHarness harness, int x, int y, int z) {
        Block block = harness.world.getBlockAt(x, y, z);
        block.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(block, harness.server.addPlayer());
        harness.server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private static void runCleanupToTheEnd(OutpostTestHarness harness) throws Exception {
        for (int i = 0; i < 200 && harness.engine.phase() == RunPhase.RESETTING; i++) {
            harness.server.getScheduler().performOneTick();
            Thread.sleep(5L);
        }
        harness.settle();
        assertEquals(RunPhase.COOLDOWN, harness.engine.phase(), "reset musi się zakończyć");
    }

    /** Fire inside the original region and inside the moved region. */
    private static void igniteBothLocations(OutpostTestHarness harness) {
        harness.world.getBlockAt(5, 64, 5).setType(Material.FIRE);
        harness.world.getBlockAt(305, 64, 305).setType(Material.FIRE);
    }

    private static void assertCleanedAtTheOriginalLocationOnly(OutpostTestHarness harness, OutpostDefinition original) {
        assertEquals(Material.AIR, harness.world.getBlockAt(5, 64, 5).getType(),
                "reset czyści pierwotną lokalizację");
        assertEquals(Material.FIRE, harness.world.getBlockAt(305, 64, 305).getType(),
                "reset nie skanuje lokalizacji z przeładowanej konfiguracji");
        assertEquals(0, harness.ticketWorld.ticketCount(), "tickety chunków zostały zwolnione");
        assertTrue(harness.regions.active().isEmpty());
        assertFalse(breakIsCancelled(harness, 8, 64, 9),
                "po zakończeniu rundy pierwotna lokalizacja nie jest już chroniona");
    }

    private static void assertRunsAtTheOriginalLocation(OutpostTestHarness harness, OutpostDefinition original) {
        assertEquals(original, harness.engine.activeOutpost().orElseThrow(),
                "odzyskanie używa zapisanej geometrii, nie bieżącego katalogu");
        assertEquals(original, harness.regions.active().orElseThrow());
        assertTrue(breakIsCancelled(harness, 8, 64, 9), "pierwotny teren pozostaje chroniony po restarcie");
        assertTrue(harness.ticketWorld.ticketCount() > 0, "chunki pierwotnej lokalizacji mają tickety");
    }

    private void restartDuringWaves(Reload reload) throws Exception {
        OutpostTestHarness harness = new OutpostTestHarness();
        OutpostDefinition original = harness.outpost;
        try {
            String runId = startFight(harness);
            harness.applyCatalog(catalogAfter(reload));
            assertEquals(original, harness.engine.activeOutpost().orElseThrow());
            assertEquals(original, OutpostDefinitionCodec.decode(harness.storedDefinition(runId)),
                    "geometria jest zapisana razem z runem");
        } catch (Throwable failure) {
            harness.close();
            throw failure;
        }

        try (OutpostTestHarness restarted = harness.restart()) {
            restarted.applyCatalog(catalogAfter(reload));
            restarted.recover();
            restarted.settle();

            assertEquals(RunPhase.WAVES, restarted.engine.phase(), "walka trwa dalej w zapisanej lokalizacji");
            assertEquals(EngineMode.RUNNING, restarted.engine.mode());
            assertRunsAtTheOriginalLocation(restarted, original);
            assertEquals(2, restarted.engine.liveMobs().size());
            for (Entity entity : restarted.world.getEntities()) {
                if (entity instanceof Zombie && restarted.engine.liveMobs().containsKey(entity.getUniqueId())) {
                    assertTrue(original.region().contains(OutpostTestHarness.WORLD, entity.getLocation().getBlockX(),
                                    entity.getLocation().getBlockY(), entity.getLocation().getBlockZ()),
                            "uzupełniona fala pojawia się w pierwotnym regionie");
                }
            }
            if (reload == Reload.MOVED) {
                assertTrue(breakIsCancelled(restarted, 310, 64, 309), "nowa lokalizacja z konfiguracji też jest chroniona");
            }

            igniteBothLocations(restarted);
            assertTrue(restarted.engine.adminStop());
            runCleanupToTheEnd(restarted);
            assertCleanedAtTheOriginalLocationOnly(restarted, original);
        }
    }

    private void restartDuringReset(Reload reload) throws Exception {
        OutpostTestHarness harness = new OutpostTestHarness();
        OutpostDefinition original = harness.outpost;
        try {
            startFight(harness);
            harness.applyCatalog(catalogAfter(reload));
            assertTrue(harness.engine.adminStop());
            harness.awaitWrites();       // RESETTING stored; the scheduler never ran the cleanup
            assertEquals(RunPhase.RESETTING, harness.persistence.repository().loadAll().run().phase());
        } catch (Throwable failure) {
            harness.close();
            throw failure;
        }

        try (OutpostTestHarness restarted = harness.restart()) {
            restarted.applyCatalog(catalogAfter(reload));
            restarted.recover();

            assertEquals(RunPhase.RESETTING, restarted.engine.phase());
            assertRunsAtTheOriginalLocation(restarted, original);
            assertEquals(0L, restarted.world.getEntities().stream().filter(Zombie.class::isInstance).count(),
                    "przerwany reset nie wraca jako walka");

            igniteBothLocations(restarted);   // before the scheduler runs the resumed cleanup
            runCleanupToTheEnd(restarted);
            assertCleanedAtTheOriginalLocationOnly(restarted, original);
            restarted.awaitWrites();
            assertEquals(RunPhase.COOLDOWN, restarted.persistence.loadAll().get().run().phase());
        }
    }

    @Test
    void aMovedOutpostResumesAtItsStoredLocationAfterARestartDuringWaves() throws Exception {
        restartDuringWaves(Reload.MOVED);
    }

    @Test
    void aRemovedOutpostResumesAtItsStoredLocationAfterARestartDuringWaves() throws Exception {
        restartDuringWaves(Reload.REMOVED);
    }

    @Test
    void aRestartDuringTheResetOfAMovedOutpostCleansTheStoredLocation() throws Exception {
        restartDuringReset(Reload.MOVED);
    }

    @Test
    void aRestartDuringTheResetOfARemovedOutpostCleansTheStoredLocation() throws Exception {
        restartDuringReset(Reload.REMOVED);
    }

    @Test
    void aLegacyRunWithoutStoredGeometryIsNeitherResumedNorDeclaredClean() throws Exception {
        OutpostTestHarness harness = new OutpostTestHarness();
        String runId;
        try {
            runId = startFight(harness);
            // A row written by a build before the geometry was persisted.
            harness.sql("UPDATE posterunki_runs SET outpost_definition = NULL");
        } catch (Throwable failure) {
            harness.close();
            throw failure;
        }

        try (OutpostTestHarness restarted = harness.restart()) {
            restarted.recover();
            restarted.awaitWrites();

            assertEquals(RunPhase.FAILED, restarted.engine.phase(), "bez geometrii walka nie jest wznawiana");
            assertTrue(restarted.engine.state().failureReason().contains("geometrii"),
                    restarted.engine.state().failureReason());
            assertFalse(restarted.engine.operational());
            assertTrue(restarted.engine.activeOutpost().isEmpty(), "nie zgadujemy geometrii z katalogu");
            assertTrue(restarted.engine.liveMobs().isEmpty());
            assertEquals(0L, restarted.world.getEntities().stream().filter(Zombie.class::isInstance).count());
            assertEquals(RunPhase.FAILED, restarted.persistence.repository().loadAll().run().phase());

            // The administrator's reset does what is possible without geometry - and says what is not.
            restarted.world.getBlockAt(5, 64, 5).setType(Material.FIRE);
            assertTrue(restarted.engine.adminReset(null));
            runCleanupToTheEnd(restarted);
            assertEquals(Material.FIRE, restarted.world.getBlockAt(5, 64, 5).getType(),
                    "bez geometrii ogień nie jest sprawdzany - lokalizacja nie jest udawana jako czysta");
            boolean audited = false;
            for (int i = 0; i < 200 && !audited; i++) {
                audited = restarted.persistence.repository().recentAudit(50).stream()
                        .anyMatch(line -> line.contains("bez-geometrii"));
                Thread.sleep(5L);
            }
            assertTrue(audited, "audyt odnotowuje niesprawdzone elementy");
            assertNotNull(runId);
        }
    }
}
