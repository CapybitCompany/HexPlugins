package hexposterunki.integration;

import hexposterunki.domain.BossStatus;
import hexposterunki.domain.RunPhase;
import hexposterunki.engine.EngineMode;
import hexposterunki.engine.Participation;
import hexposterunki.listener.RegionProtectionListener;
import hexposterunki.persistence.RewardClaim;
import hexposterunki.support.FakeBossAdapter;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review finding 6: a failed state write freezes the fight for the scheduler <i>and</i> every event
 * handler, while region protection stays active and a later successful write resumes in a controlled
 * way.
 *
 * <p>The database failure is real: the state table is renamed, so the production transaction fails
 * inside H2. Events travel through the registered listeners.
 */
class PersistencePauseIntegrationTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private Player member(OutpostTestHarness harness, String name, int x) {
        Player player = harness.server.addPlayer(name);
        harness.towns.assign(player.getUniqueId(), TOWN, "Rycerze");
        player.teleport(harness.inside(x, 64, 8));
        return player;
    }

    private void reachWaves(OutpostTestHarness harness) throws Exception {
        harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test");
        harness.tick();
        harness.tick();
        harness.settle();
        assertEquals(RunPhase.WAVES, harness.engine.phase());
        assertTrue(harness.persistence.healthy());
    }

    private List<LivingEntity> liveMobs(OutpostTestHarness harness) {
        List<LivingEntity> mobs = new ArrayList<>();
        for (Entity entity : harness.world.getEntities()) {
            if (harness.engine.liveMobs().containsKey(entity.getUniqueId())) {
                mobs.add((LivingEntity) entity);
            }
        }
        return mobs;
    }

    /** Hides the state table and lets the next state write fail. No engine tick is run. */
    private void breakPersistence(OutpostTestHarness harness) throws Exception {
        harness.sql("ALTER TABLE posterunki_state RENAME TO ukryty_stan");
        harness.engine.state().markChanged();
        harness.engine.persistState();
        for (int i = 0; i < 400 && harness.persistence.healthy(); i++) {
            Thread.sleep(5L);
        }
        assertFalse(harness.persistence.healthy(), "zapis musiał się nie udać");
        assertEquals(EngineMode.PERSISTENCE_PAUSED, harness.engine.mode(),
                "handlery widzą pauzę natychmiast, bez czekania na tick");
    }

    /** Restores the table and runs ticks until the engine resumed through its controlled path. */
    private void repairPersistence(OutpostTestHarness harness) throws Exception {
        harness.sql("ALTER TABLE ukryty_stan RENAME TO posterunki_state");
        for (int i = 0; i < 200 && harness.engine.mode() != EngineMode.RUNNING; i++) {
            harness.engine.tick();
            Thread.sleep(5L);
        }
        assertEquals(EngineMode.RUNNING, harness.engine.mode());
        harness.awaitWrites();
        assertTrue(harness.persistence.healthy(), harness.persistence.lastError());
    }

    /** Reads the participant table directly; the state table is hidden while persistence is broken. */
    private int storedParticipantRows(OutpostTestHarness harness) throws Exception {
        try (var statement = harness.database().createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM posterunki_participants")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private boolean cancelled(EntityDamageEvent event, OutpostTestHarness harness) {
        harness.server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private static DamageSource generic() {
        return DamageSource.builder(DamageType.GENERIC).build();
    }

    @Test
    void everyKindOfEncounterDamageIsBlockedWhilePaused() throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            Player player = member(harness, "Obronca", 8);
            reachWaves(harness);
            LivingEntity mob = liveMobs(harness).get(0);
            breakPersistence(harness);

            assertEquals(Participation.SUSPENDED, harness.engine.participationOf(player));
            assertTrue(cancelled(new EntityDamageByEntityEvent(player, mob,
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK, generic(), 4.0), harness), "cios wręcz");

            player.addAttachment(harness.plugin, RegionProtectionListener.BYPASS, true);
            assertEquals(Participation.SUSPENDED, harness.engine.participationOrBypass(player, true));
            assertTrue(cancelled(new EntityDamageByEntityEvent(player, mob,
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK, generic(), 4.0), harness),
                    "uprawnienie administratora nie odblokowuje wstrzymanej walki");

            assertTrue(cancelled(new EntityDamageEvent(mob, EntityDamageEvent.DamageCause.FIRE_TICK,
                    DamageSource.builder(DamageType.ON_FIRE).build(), 1.0), harness), "obrażenia pośrednie");

            Arrow arrow = harness.world.spawn(harness.inside(9, 64, 9), Arrow.class);
            assertTrue(cancelled(new EntityDamageByEntityEvent(arrow, mob,
                    EntityDamageEvent.DamageCause.PROJECTILE, generic(), 3.0), harness), "pocisk w locie");

            assertTrue(cancelled(new EntityDamageByEntityEvent(mob, player,
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK, generic(), 3.0), harness),
                    "moby wydarzenia też nie zadają obrażeń");

            Zombie unrelated = harness.world.spawn(harness.outside(), Zombie.class);
            assertFalse(cancelled(new EntityDamageEvent(unrelated, EntityDamageEvent.DamageCause.FIRE_TICK,
                    DamageSource.builder(DamageType.ON_FIRE).build(), 1.0), harness),
                    "pauza dotyczy tylko encji wydarzenia");

            // Protection is permanent and does not depend on the operating mode.
            Block block = harness.inside(8, 64, 9).getBlock();
            block.setType(Material.STONE);
            BlockBreakEvent breakEvent = new BlockBreakEvent(block, harness.server.addPlayer("Gracz"));
            harness.server.getPluginManager().callEvent(breakEvent);
            assertTrue(breakEvent.isCancelled(), "ochrona regionu działa także podczas pauzy");
        }
    }

    @Test
    void deathsWhilePausedCreditNobodyAndProgressNothing() throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            Player player = member(harness, "Obronca", 8);
            reachWaves(harness);
            breakPersistence(harness);

            for (LivingEntity mob : liveMobs(harness)) {
                harness.engine.onRunMobDeath(mob, player);   // explicit killer
                mob.setHealth(0.0D);                          // real EntityDeathEvent via the listener
            }
            for (int i = 0; i < 5; i++) {
                harness.engine.tick();
            }

            assertEquals(0, harness.engine.participation().ledger().kills(player.getUniqueId()));
            assertEquals(RunPhase.WAVES, harness.engine.phase(), "pusta fala podczas pauzy nie jest zwycięstwem");
            assertEquals(2, harness.engine.liveMobs().size(), "fala nie jest odliczana podczas pauzy");
            assertTrue(harness.persistence.repository().loadClaims(RewardClaim.Status.CLAIMED).isEmpty());

            repairPersistence(harness);
            harness.settle();
            assertEquals(0, harness.engine.participation().ledger().kills(player.getUniqueId()),
                    "zabójstwa z czasu pauzy nie są przyznawane także po wznowieniu");
            assertTrue(harness.persistence.loadAll().get().participants().isEmpty());
        }
    }

    @Test
    void aBossDeathWhilePausedIsNotAVictoryAndIsHandledAfterResuming() throws Exception {
        FakeBossAdapter bossEngine = new FakeBossAdapter();
        try (OutpostTestHarness harness = OutpostTestHarness.withBoss(bossEngine)) {
            Player player = member(harness, "Obronca", 8);
            reachWaves(harness);
            for (LivingEntity mob : liveMobs(harness)) {
                harness.engine.onRunMobDeath(mob, player);
                mob.remove();
            }
            harness.settle();
            harness.tick();
            harness.settle();
            assertEquals(RunPhase.BOSS, harness.engine.phase());
            String runId = harness.engine.state().runId().orElseThrow();
            LivingEntity boss = (LivingEntity) Bukkit.getEntity(harness.engine.state().bossEntityId().orElseThrow());

            breakPersistence(harness);
            boss.setHealth(0.0D);   // real EntityDeathEvent while the writes fail
            for (int i = 0; i < 5; i++) {
                harness.engine.tick();
            }

            assertEquals(RunPhase.BOSS, harness.engine.phase(), "śmierć bossa podczas pauzy nie kończy runu");
            assertNotEquals(BossStatus.DEAD, harness.engine.state().bossStatus());
            assertFalse(harness.rewards.ledger().isFrozen(runId));
            assertTrue(harness.persistence.repository().loadCompletionStats(runId).isEmpty());

            repairPersistence(harness);
            for (int i = 0; i < 6; i++) {
                harness.tick();
            }
            harness.settle();
            assertEquals(RunPhase.BOSS, harness.engine.phase(), "po wznowieniu brak bossa to nie zwycięstwo");
            assertEquals(2, bossEngine.spawns(), "zaginiony boss dostaje jedną próbę odtworzenia");
            assertEquals(BossStatus.ALIVE, harness.engine.state().bossStatus());
        }
    }

    @Test
    void progressLostWhilePausedIsStoredOnceWritesWorkAgain() throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            Player dying = member(harness, "Poleglý", 8);
            Player leaving = member(harness, "Uciekinier", 9);
            reachWaves(harness);
            List<LivingEntity> mobs = liveMobs(harness);
            harness.engine.onRunMobDeath(mobs.get(0), dying);
            harness.engine.onRunMobDeath(mobs.get(1), leaving);
            harness.settle();
            assertEquals(2, harness.persistence.loadAll().get().participants().size());

            breakPersistence(harness);
            dying.setHealth(0.0D);
            leaving.teleport(harness.outside());

            assertTrue(harness.engine.participation().ledger().entries().isEmpty(),
                    "śmierć i opuszczenie zerują postęp także podczas pauzy");
            assertEquals(2, storedParticipantRows(harness), "baza jest jeszcze niedostępna do zapisu");

            repairPersistence(harness);
            harness.awaitWrites();
            assertTrue(harness.persistence.loadAll().get().participants().isEmpty(),
                    "po odzyskaniu zapisu utracony postęp nie może pozostać w bazie");
        }
    }

    @Test
    void theEngineResumesInAControlledWay() throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            Player player = member(harness, "Obronca", 8);
            reachWaves(harness);
            LivingEntity mob = liveMobs(harness).get(0);
            long deadline = System.currentTimeMillis() + 60_000L;
            harness.engine.state().setNextPhaseAt(deadline);
            harness.engine.persistState();
            harness.settle();

            breakPersistence(harness);
            harness.engine.tick();   // the scheduler notices the pause
            Thread.sleep(300L);
            repairPersistence(harness);

            assertTrue(harness.engine.state().nextPhaseAtMillis() >= deadline + 300L,
                    "termin fazy przesuwa się o czas pauzy");
            assertEquals(Participation.ELIGIBLE, harness.engine.participationOf(player));
            EntityDamageByEntityEvent hit = new EntityDamageByEntityEvent(player, mob,
                    EntityDamageEvent.DamageCause.ENTITY_ATTACK, generic(), 4.0);
            harness.server.getPluginManager().callEvent(hit);
            assertFalse(hit.isCancelled(), "po wznowieniu walka toczy się dalej");

            boolean audited = false;
            for (int i = 0; i < 200 && !audited; i++) {
                audited = harness.persistence.repository().recentAudit(20).stream()
                        .anyMatch(line -> line.contains("PERSISTENCE_RESUMED"));
                Thread.sleep(5L);
            }
            assertTrue(audited, "wznowienie jest odnotowane w audycie");
            assertEquals(harness.engine.state().nextPhaseAtMillis(),
                    harness.persistence.loadAll().get().run().nextPhaseAtMillis(), "wznowiony stan jest zapisany");
        }
    }
}
