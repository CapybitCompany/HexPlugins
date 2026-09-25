package hexposterunki.integration;

import hexposterunki.domain.RunPhase;
import hexposterunki.engine.Participation;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 5 regression through the real event pipeline: the registered {@code EncounterListener}
 * receives genuine Bukkit events on a mock server and talks to the real engine.
 *
 * <p>Players are placed <b>before</b> the run starts on purpose - teleporting into an active
 * outpost is blocked by the plugin itself, which
 * {@link #teleportingIntoAnActiveOutpostIsBlocked()} pins down separately.
 */
class EncounterDamageIntegrationTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private OutpostTestHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = new OutpostTestHarness();
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    /** Creates a player, optionally in a town, and places them before any run exists. */
    private Player placeInside(String name, UUID town, double x, double z) {
        Player player = harness.server.addPlayer(name);
        if (town != null) {
            harness.towns.assign(player.getUniqueId(), town, town.equals(TOWN) ? "Rycerze" : "Najeźdźcy");
        }
        player.teleport(harness.inside(x, 64, z));
        return player;
    }

    /** Starts the run and advances into the wave phase. */
    private void startFight() throws Exception {
        harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test");
        harness.tick();   // claim -> PREPARATION (preparation seconds are 0 in the test config)
        harness.tick();   // -> WAVES, wave spawned
        harness.settle();
        assertEquals(RunPhase.WAVES, harness.engine.phase(),
                "powód: " + harness.engine.state().failureReason() + " / " + harness.engine.stoppedReason());
        // The engine pauses on an unhealthy persistence layer, which would silently skip every
        // later tick - so the harness must prove the database side is fine.
        assertTrue(harness.persistence.healthy(), "zapis stanu: " + harness.persistence.lastError());
    }

    private LivingEntity anyRunMob() {
        UUID id = harness.engine.liveMobs().keySet().iterator().next();
        return (LivingEntity) harness.world.getEntities().stream()
                .filter(entity -> entity.getUniqueId().equals(id))
                .findFirst().orElseThrow();
    }

    /**
     * Fires a real {@link EntityDamageByEntityEvent} through the plugin manager, so the registered
     * {@code EncounterListener} handles it exactly as it would on a live server.
     *
     * @return true when the listener let the damage stand
     */
    private boolean dealsDamage(org.bukkit.entity.Entity damager, LivingEntity victim) {
        DamageSource source = DamageSource.builder(DamageType.GENERIC).build();
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(damager, victim,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 4.0);
        harness.server.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    @Test
    void aMemberInsideTheRegionMayDamageTheDefenders() throws Exception {
        Player member = placeInside("Obronca", TOWN, 8, 8);
        startFight();

        assertTrue(dealsDamage(member, anyRunMob()), "członek kontrolującej drużyny może zadawać obrażenia");
        assertEquals(Participation.ELIGIBLE, harness.engine.participationOf(member));
    }

    @Test
    void shootingInFromOutsideTheRegionIsBlocked() throws Exception {
        Player member = placeInside("Obronca", TOWN, 8, 8);
        startFight();
        LivingEntity mob = anyRunMob();

        // The member steps out of the fortress and keeps attacking from the outside.
        member.teleport(harness.outside());
        harness.tick();

        assertFalse(dealsDamage(member, mob), "atak spoza regionu musi zostać zablokowany");
        assertEquals(Participation.OUTSIDE_REGION, harness.engine.participationOf(member));
    }

    @Test
    void aForeignTownMemberCannotDamageTheDefenders() throws Exception {
        placeInside("Obronca", TOWN, 8, 8);
        Player intruder = placeInside("Najezdzca", OTHER_TOWN, 9, 9);
        startFight();

        assertTrue(harness.engine.participation().isInside(intruder.getUniqueId()),
                "najeźdźca stoi na terenie posterunku");
        assertFalse(dealsDamage(intruder, anyRunMob()));
        assertEquals(Participation.WRONG_TOWN, harness.engine.participationOf(intruder));
    }

    @Test
    void aTownlessPlayerCannotDamageTheDefenders() throws Exception {
        placeInside("Obronca", TOWN, 8, 8);
        Player wanderer = placeInside("Wedrowiec", null, 9, 9);
        startFight();

        assertFalse(dealsDamage(wanderer, anyRunMob()));
        assertEquals(Participation.NO_TOWN, harness.engine.participationOf(wanderer));
    }

    @Test
    void aProjectileFiredBeforeLeavingIsJudgedByTheShootersCurrentState() throws Exception {
        Player member = placeInside("Lucznik", TOWN, 8, 8);
        startFight();
        LivingEntity mob = anyRunMob();

        Arrow arrow = harness.world.spawn(harness.inside(8, 64, 8), Arrow.class);
        try {
            arrow.setShooter(member);
        } catch (RuntimeException | AssertionError unsupported) {
            // MockBukkit does not implement Projectile#setShooter. The rule itself is covered
            // without a server by ParticipationRulesTest; skip only the event-pipeline variant.
            org.junit.jupiter.api.Assumptions.abort(
                    "MockBukkit nie obsługuje Projectile#setShooter: " + unsupported);
            return;
        }

        // The arrow is already in flight when the shooter leaves the fortress.
        member.teleport(harness.outside());
        harness.tick();

        assertFalse(dealsDamage(arrow, mob),
                "trafienie po opuszczeniu regionu nie może już zadać obrażeń eventowych");
    }

    @Test
    void aKillFromOutsideStillClearsTheWaveButCreditsNobody() throws Exception {
        Player member = placeInside("Obronca", TOWN, 8, 8);
        startFight();
        int before = harness.engine.liveMobs().size();
        assertTrue(before > 0);

        member.teleport(harness.outside());
        harness.tick();

        Zombie mob = (Zombie) anyRunMob();
        harness.engine.onRunMobDeath(mob, member);
        harness.settle();

        assertEquals(before - 1, harness.engine.liveMobs().size(),
                "śmierć moba nadal zmniejsza liczbę przeciwników");
        assertEquals(0, harness.engine.participation().ledger().kills(member.getUniqueId()),
                "gracz spoza regionu nie dostaje zabójstwa");
    }

    @Test
    void aValidParticipantGetsExactlyOneKillCredit() throws Exception {
        Player member = placeInside("Obronca", TOWN, 8, 8);
        startFight();
        Zombie mob = (Zombie) anyRunMob();

        harness.engine.onRunMobDeath(mob, member);
        harness.settle();
        assertEquals(1, harness.engine.participation().ledger().kills(member.getUniqueId()));

        // The same mob arriving twice (e.g. a duplicated event) must not double-count.
        harness.engine.onRunMobDeath(mob, member);
        harness.settle();
        assertEquals(1, harness.engine.participation().ledger().kills(member.getUniqueId()),
                "ten sam mob nie może dać dwóch zabójstw");
    }

    @Test
    void progressIsLostWhenTheRegionIsLeft() throws Exception {
        Player member = placeInside("Obronca", TOWN, 8, 8);
        startFight();
        harness.engine.onRunMobDeath((Zombie) anyRunMob(), member);
        assertEquals(1, harness.engine.participation().ledger().kills(member.getUniqueId()));

        member.teleport(harness.outside());
        harness.tick();
        harness.settle();

        assertEquals(0, harness.engine.participation().ledger().kills(member.getUniqueId()),
                "opuszczenie posterunku zeruje postęp");
    }

    @Test
    void teleportingIntoAnActiveOutpostIsBlocked() throws Exception {
        placeInside("Obronca", TOWN, 8, 8);
        startFight();

        Player latecomer = harness.server.addPlayer("Spozniony");
        harness.towns.assign(latecomer.getUniqueId(), TOWN, "Rycerze");
        latecomer.teleport(harness.outside());
        harness.tick();

        latecomer.teleport(harness.inside(10, 64, 10));

        assertFalse(harness.engine.activeRegionContains(latecomer.getLocation()),
                "teleportacja na teren aktywnego posterunku musi zostać zablokowana");
        assertFalse(harness.engine.participation().isInside(latecomer.getUniqueId()));
    }
}
