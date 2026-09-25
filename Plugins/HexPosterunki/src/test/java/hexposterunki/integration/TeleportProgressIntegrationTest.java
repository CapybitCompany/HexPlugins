package hexposterunki.integration;

import hexposterunki.domain.KillEntry;
import hexposterunki.domain.RunPhase;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full review, finding 5: progress is only lost when a teleport really leaves the region - judged after
 * every other plugin had its say, with the final destination. Other plugins are represented by plain
 * listeners on the real Bukkit event bus.
 */
class TeleportProgressIntegrationTest {

    private OutpostTestHarness harness;
    private Player member;

    @BeforeEach
    void setUp() throws Exception {
        harness = new OutpostTestHarness();
        member = harness.server.addPlayer("Obronca");
        harness.towns.assign(member.getUniqueId(), UUID.randomUUID(), "Rycerze");
        member.teleport(harness.inside(8, 64, 8));
        assertTrue(harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test"));
        harness.tick();
        harness.tick();
        harness.settle();
        assertEquals(RunPhase.WAVES, harness.engine.phase());
        Entity mob = harness.world.getEntities().stream()
                .filter(entity -> harness.engine.liveMobs().containsKey(entity.getUniqueId()))
                .findFirst().orElseThrow();
        harness.engine.onRunMobDeath((LivingEntity) mob, member);
        mob.remove();
        harness.settle();
        assertEquals(1, kills());
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    private int kills() {
        return harness.engine.participation().ledger().kills(member.getUniqueId());
    }

    private void anotherPlugin(EventPriority priority, Consumer<PlayerTeleportEvent> behaviour) {
        Listener listener = new Listener() {
        };
        harness.server.getPluginManager().registerEvent(PlayerTeleportEvent.class, listener, priority,
                (ignored, event) -> behaviour.accept((PlayerTeleportEvent) event), harness.plugin, false);
    }

    private List<Integer> storedKills() throws Exception {
        harness.awaitWrites();
        return harness.persistence.loadAll().get().participants().stream().map(KillEntry::kills).toList();
    }

    @Test
    void aTeleportCancelledLaterByAnotherPluginKeepsPositionParticipationAndKills() throws Exception {
        anotherPlugin(EventPriority.HIGHEST, event -> event.setCancelled(true));

        assertFalse(member.teleport(harness.outside()));

        assertTrue(harness.engine.activeRegionContains(member.getLocation()), "gracz nadal stoi na posterunku");
        assertTrue(harness.engine.participation().isInside(member.getUniqueId()));
        assertEquals(1, kills(), "anulowany teleport nie zeruje zabójstw");
        assertEquals(List.of(1), storedKills());
    }

    @Test
    void aTeleportRedirectedBackIntoTheRegionKeepsTheKills() throws Exception {
        Location stillInside = harness.inside(12, 64, 12);
        anotherPlugin(EventPriority.HIGH, event -> event.setTo(stillInside));

        member.teleport(harness.outside());

        assertTrue(harness.engine.activeRegionContains(member.getLocation()));
        assertEquals(1, kills(), "ostateczny cel teleportu leży na posterunku");
    }

    @Test
    void aSuccessfulTeleportOutStillDropsTheProgress() throws Exception {
        assertTrue(member.teleport(harness.outside()));

        assertFalse(harness.engine.participation().isInside(member.getUniqueId()));
        assertEquals(0, kills(), "wyjście teleportem nadal zeruje postęp");
        assertTrue(storedKills().isEmpty());
    }

    @Test
    void aDestinationRedirectedIntoTheRegionByAnotherPluginIsStillBlocked() {
        Player outsider = harness.server.addPlayer("Spozniony");
        outsider.teleport(harness.outside());
        anotherPlugin(EventPriority.HIGH, event -> event.setTo(harness.inside(10, 64, 10)));

        outsider.teleport(harness.outside().add(5, 0, 5));

        assertFalse(harness.engine.activeRegionContains(outsider.getLocation()),
                "zmieniony cel na teren aktywnego posterunku nadal jest blokowany");
    }
}
