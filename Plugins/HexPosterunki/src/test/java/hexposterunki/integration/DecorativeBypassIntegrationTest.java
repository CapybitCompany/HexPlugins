package hexposterunki.integration;

import hexposterunki.listener.RegionProtectionListener;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Final review, finding 3: the admin bypass is judged in one place for decorative entities. A general
 * handler that cancelled regardless used to undo the exception the by-entity handlers grant, so an
 * administrator could not remove an armor stand or an item frame. Everything runs through the real
 * Bukkit event bus.
 */
class DecorativeBypassIntegrationTest {

    private OutpostTestHarness harness;
    private ArmorStand stand;
    private ItemFrame frame;
    private Player admin;
    private Player visitor;

    @BeforeEach
    void setUp() throws Exception {
        harness = new OutpostTestHarness();
        stand = harness.world.spawn(harness.inside(5, 64, 5), ArmorStand.class);
        frame = harness.world.spawn(harness.inside(6, 64, 5), ItemFrame.class);
        admin = harness.server.addPlayer("Admin");
        admin.addAttachment(harness.plugin, RegionProtectionListener.BYPASS, true);
        visitor = harness.server.addPlayer("Gracz");
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
    void anAdministratorWithTheBypassPermissionMayRemoveDecorations() {
        assertFalse(cancelled(new EntityDamageByEntityEvent(admin, stand,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2)), "rusztowanie zbroi dla administratora");
        assertFalse(cancelled(new HangingBreakByEntityEvent(frame, admin)), "ramka dla administratora");
    }

    @Test
    void ordinaryPlayersAndTheEnvironmentStayBlocked() {
        assertTrue(cancelled(new EntityDamageByEntityEvent(visitor, stand,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2)), "zwykły gracz nie niszczy dekoracji");
        assertTrue(cancelled(new HangingBreakByEntityEvent(frame, visitor)));

        Zombie zombie = harness.world.spawn(harness.inside(7, 64, 5), Zombie.class);
        assertTrue(cancelled(new EntityDamageByEntityEvent(zombie, stand,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2)), "obce moby też nie");
        assertTrue(cancelled(new EntityDamageEvent(stand, EntityDamageEvent.DamageCause.FIRE, 2.0)),
                "ogień nie niszczy dekoracji posterunku");
        assertTrue(cancelled(new HangingBreakEvent(frame, HangingBreakEvent.RemoveCause.PHYSICS)),
                "fizyka nie zrywa ramki");
    }

    @Test
    void anEventAnotherPluginAlreadyCancelledStaysCancelled() {
        EntityDamageByEntityEvent damage = new EntityDamageByEntityEvent(admin, stand,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2);
        damage.setCancelled(true);
        HangingBreakByEntityEvent hanging = new HangingBreakByEntityEvent(frame, admin);
        hanging.setCancelled(true);

        assertTrue(cancelled(damage), "cudzej decyzji o anulowaniu nie cofamy");
        assertTrue(cancelled(hanging));
    }

    @Test
    void decorationsOutsideAnyRegionAreNotTouched() {
        ArmorStand outside = harness.world.spawn(harness.outside(), ArmorStand.class);
        assertFalse(cancelled(new EntityDamageByEntityEvent(visitor, outside,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2)), "poza regionem plugin nie ingeruje");
        assertFalse(cancelled(new EntityDamageEvent(outside, EntityDamageEvent.DamageCause.FIRE, 2.0)));
    }
}
