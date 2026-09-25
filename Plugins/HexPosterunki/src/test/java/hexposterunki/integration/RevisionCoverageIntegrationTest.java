package hexposterunki.integration;

import hexposterunki.domain.KillEntry;
import hexposterunki.domain.RunPhase;
import hexposterunki.persistence.PosterunkiRepository;
import hexposterunki.support.OutpostTestHarness;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review finding 1: a kill reset must reach the database although it does not touch the state
 * machine.
 *
 * <p>Each scenario first lets the kill be stored and fully settled, then triggers exactly one
 * progress loss through the real listener pipeline - with no further fight, wave or phase change -
 * and reads the state back through the production repository SQL (H2 in MySQL mode).
 */
class RevisionCoverageIntegrationTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private OutpostTestHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = new OutpostTestHarness();
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    /** Fight with one stored kill; returns the revision under which the kill is stored. */
    private Player fighterWithStoredKill() throws Exception {
        Player player = harness.server.addPlayer("Wojownik");
        harness.towns.assign(player.getUniqueId(), TOWN, "Rycerze");
        player.teleport(harness.inside(8, 64, 8));
        harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test");
        harness.tick();
        harness.tick();
        harness.settle();
        assertEquals(RunPhase.WAVES, harness.engine.phase());

        harness.engine.onRunMobDeath(anyRunMob(), player);
        harness.settle();
        PosterunkiRepository.LoadedState stored = harness.persistence.loadAll().get();
        assertEquals(List.of(1), stored.participants().stream().map(KillEntry::kills).toList(),
                "zabójstwo musi być zapisane, zanim sprawdzimy jego utratę");
        assertFalse(harness.persistence.hasPendingWork());
        return player;
    }

    private LivingEntity anyRunMob() {
        UUID id = harness.engine.liveMobs().keySet().iterator().next();
        return (LivingEntity) harness.world.getEntities().stream()
                .filter(entity -> entity.getUniqueId().equals(id)).findFirst().orElseThrow();
    }

    private long storedRevision() throws Exception {
        return harness.persistence.loadAll().get().run().revision();
    }

    private void assertNoStoredKills(long revisionBefore, String reason) throws Exception {
        harness.awaitWrites();
        PosterunkiRepository.LoadedState stored = harness.persistence.loadAll().get();
        assertTrue(harness.persistence.healthy(), "zapis stanu: " + harness.persistence.lastError());
        assertTrue(stored.consistent());
        assertTrue(stored.participants().isEmpty(), reason + ": w bazie nadal " + stored.participants());
        assertTrue(stored.run().revision() > revisionBefore,
                reason + ": utrata postępu wymaga nowej rewizji, a zapisano " + stored.run().revision());
        assertEquals(RunPhase.WAVES, stored.run().phase(), "żadna inna zmiana stanu nie była potrzebna");
    }

    @Test
    void deathDropsTheStoredKillWithoutAnyFurtherMutation() throws Exception {
        Player player = fighterWithStoredKill();
        long before = storedRevision();

        player.setHealth(0.0D);   // real PlayerDeathEvent through the registered listener

        assertTrue(harness.engine.participation().ledger().entries().isEmpty());
        assertNoStoredKills(before, "śmierć");
    }

    @Test
    void leavingTheRegionDropsTheStoredKillWithoutAnyFurtherMutation() throws Exception {
        Player player = fighterWithStoredKill();
        long before = storedRevision();

        player.teleport(harness.outside());   // real PlayerTeleportEvent, no engine tick

        assertNoStoredKills(before, "opuszczenie regionu");
    }

    @Test
    void logoutDropsTheStoredKillWithoutAnyFurtherMutation() throws Exception {
        Player player = fighterWithStoredKill();
        long before = storedRevision();

        harness.server.getPluginManager().callEvent(new PlayerQuitEvent(player,
                Component.text("wyszedł"), PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertNoStoredKills(before, "wylogowanie");
    }

    @Test
    void aDroppedKillDoesNotComeBackAfterARestart() throws Exception {
        Player player = fighterWithStoredKill();
        player.setHealth(0.0D);
        harness.awaitWrites();

        try (OutpostTestHarness restarted = harness.restart()) {
            restarted.recover();
            assertTrue(restarted.engine.participation().ledger().entries().isEmpty(),
                    "po restarcie wyzerowany postęp nie może wrócić");
        }
    }

    @Test
    void aTrackedEntityDroppedOutsideTheStateMachineIsStoredToo() throws Exception {
        fighterWithStoredKill();
        long before = storedRevision();
        UUID remaining = harness.engine.liveMobs().keySet().iterator().next();

        // Recovery and containment work on this map directly; no state-machine field changes.
        harness.engine.liveMobs().remove(remaining);
        harness.engine.persistState();
        harness.awaitWrites();

        PosterunkiRepository.LoadedState stored = harness.persistence.loadAll().get();
        assertTrue(stored.entities().stream().noneMatch(entity -> entity.entityId().equals(remaining)),
                "usunięta encja nie może zostać w bazie: " + stored.entities());
        assertTrue(stored.run().revision() > before);
    }

    @Test
    void anUnchangedStateReusesItsRevisionAndARetryWritesTheSameRevision() throws Exception {
        Player player = fighterWithStoredKill();
        long committed = harness.persistence.lastCommittedRevision();

        harness.engine.persistState();
        harness.engine.persistState();
        harness.awaitWrites();
        assertEquals(committed, harness.engine.state().revision(),
                "bez zmian nie powstaje nowa rewizja");

        harness.sql("ALTER TABLE posterunki_state RENAME TO ukryty_stan");
        player.setHealth(0.0D);
        harness.awaitWrites();
        assertFalse(harness.persistence.healthy());
        long pending = harness.engine.state().revision();
        assertTrue(pending > committed);

        for (int attempt = 0; attempt < 3; attempt++) {
            harness.engine.tick();   // paused: retries the very same snapshot
            harness.awaitWrites();
        }
        assertEquals(pending, harness.engine.state().revision(), "ponowienie nie zużywa nowych rewizji");

        harness.sql("ALTER TABLE ukryty_stan RENAME TO posterunki_state");
        harness.engine.tick();
        harness.awaitWrites();
        assertTrue(harness.persistence.healthy(), "zapis stanu: " + harness.persistence.lastError());
        PosterunkiRepository.LoadedState stored = harness.persistence.loadAll().get();
        assertTrue(stored.participants().isEmpty());
        assertTrue(stored.run().revision() >= pending);
    }
}
