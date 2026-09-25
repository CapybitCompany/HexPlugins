package hexposterunki.integration;

import hexposterunki.domain.RunPhase;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full review, finding 4: encounter mobs neither carry riders nor board vehicles - including a boat
 * brought into the fortress from outside - while ordinary mounting stays untouched.
 */
class EncounterMountIntegrationTest {

    private OutpostTestHarness harness;
    private Entity waveMob;

    @BeforeEach
    void setUp() throws Exception {
        harness = new OutpostTestHarness();
        Player member = harness.server.addPlayer("Obronca");
        harness.towns.assign(member.getUniqueId(), UUID.randomUUID(), "Rycerze");
        member.teleport(harness.inside(8, 64, 8));
        assertTrue(harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test"));
        harness.tick();
        harness.tick();
        harness.settle();
        assertEquals(RunPhase.WAVES, harness.engine.phase());
        waveMob = harness.world.getEntities().stream()
                .filter(entity -> harness.engine.liveMobs().containsKey(entity.getUniqueId()))
                .findFirst().orElseThrow();
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    private <T extends Event & Cancellable> boolean cancelled(T event) {
        harness.server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void aWaveMobCannotBoardABoatBroughtInFromOutside() {
        Boat boat = harness.world.spawn(harness.outside(), Boat.class);
        boat.teleport(harness.inside(4, 64, 4));   // pushed into the fortress

        assertTrue(cancelled(new EntityMountEvent(waveMob, boat)), "mob wydarzenia nie wsiada do łodzi");
        assertTrue(cancelled(new VehicleEnterEvent(boat, waveMob)), "także zdarzenie wejścia do pojazdu");
    }

    @Test
    void nobodyMayRideAWaveMob() {
        Player rider = harness.server.addPlayer("Jezdziec");
        assertTrue(cancelled(new EntityMountEvent(rider, waveMob)), "nikt nie dosiada moba wydarzenia");
    }

    @Test
    void ordinaryMountingStaysAllowed() {
        Boat boat = harness.world.spawn(harness.inside(4, 64, 4), Boat.class);
        Zombie ordinary = harness.world.spawn(harness.outside(), Zombie.class);
        Player player = harness.server.addPlayer("Zeglarz");

        assertFalse(cancelled(new EntityMountEvent(ordinary, boat)), "zwykły mob może wsiąść do łodzi");
        assertFalse(cancelled(new VehicleEnterEvent(boat, ordinary)));
        assertFalse(cancelled(new EntityMountEvent(player, boat)), "gracz może wsiąść do łodzi");
    }
}
