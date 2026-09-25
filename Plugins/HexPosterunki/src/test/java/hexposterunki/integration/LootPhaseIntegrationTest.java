package hexposterunki.integration;

import hexposterunki.config.LootConfig;
import hexposterunki.config.PosterunkiConfig;
import hexposterunki.domain.RunPhase;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 3 regression on the running engine: a victory opens a real loot window instead of being
 * followed by the reset on the very next tick.
 */
class LootPhaseIntegrationTest {

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

    private Inventory lootContainer() {
        var location = harness.inside(10, 64, 10);
        location.getBlock().setType(Material.HOPPER);
        return ((org.bukkit.block.Container) location.getBlock().getState()).getInventory();
    }

    /** Runs a full encounter to victory with one member of the controlling town. */
    private Player winTheOutpost() throws Exception {
        Player member = harness.server.addPlayer("Zwyciezca");
        harness.towns.assign(member.getUniqueId(), TOWN, "Rycerze");
        member.teleport(harness.inside(8, 64, 8));

        harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test");
        harness.tick();   // claim -> PREPARATION
        harness.tick();   // -> WAVES
        harness.settle();
        assertEquals(RunPhase.WAVES, harness.engine.phase());

        for (UUID mobId : List.copyOf(harness.engine.liveMobs().keySet())) {
            harness.world.getEntities().stream()
                    .filter(entity -> entity.getUniqueId().equals(mobId))
                    .findFirst()
                    .ifPresent(entity -> harness.engine.onRunMobDeath((Zombie) entity, member));
        }
        harness.settle();

        harness.tick();   // wave cleared -> COMPLETED (no boss configured)
        harness.settle();
        return member;
    }

    @Test
    void aVictoryOpensTheLootWindowInsteadOfResettingImmediately() throws Exception {
        winTheOutpost();
        assertEquals(RunPhase.COMPLETED, harness.engine.phase());

        harness.tick();   // COMPLETED -> LOOTING
        assertEquals(RunPhase.LOOTING, harness.engine.phase());
        assertTrue(harness.engine.state().lootUntilMillis() > System.currentTimeMillis());
    }

    @Test
    void theContainerIsNotEmptiedOnTheTickAfterTheVictory() throws Exception {
        winTheOutpost();
        Inventory container = lootContainer();
        container.addItem(new ItemStack(Material.DIAMOND, 5));

        harness.tick();   // -> LOOTING
        harness.tick();   // still LOOTING
        harness.tick();

        assertEquals(RunPhase.LOOTING, harness.engine.phase());
        assertTrue(container.contains(Material.DIAMOND),
                "łupy muszą przetrwać kolejne ticki po zwycięstwie");
    }

    @Test
    void theWinnerKeepsControlThroughoutTheLootWindow() throws Exception {
        winTheOutpost();
        harness.tick();

        assertEquals(RunPhase.LOOTING, harness.engine.phase());
        assertEquals(TOWN, harness.engine.state().controllingTown().orElseThrow());
        assertFalse(harness.engine.phase().allowsControlChange(),
                "w trakcie zbierania łupów nikt nie przejmie posterunku");
    }

    @Test
    void theResetHappensOnlyAfterTheLootWindowElapsed() throws Exception {
        // One second of loot time, so the window can actually expire inside a test.
        PosterunkiConfig shortWindow = harness.config(1L);
        harness.setConfig(shortWindow);

        winTheOutpost();
        harness.tick();
        assertEquals(RunPhase.LOOTING, harness.engine.phase());

        Thread.sleep(1_100L);
        harness.tick();      // window elapsed -> reset starts
        harness.settle();
        for (int i = 0; i < 10; i++) {
            harness.tick();
            harness.settle();
        }

        assertEquals(RunPhase.COOLDOWN, harness.engine.phase(),
                "reset następuje dopiero po upływie okna łupów");
        assertFalse(harness.engine.resetInProgress(), "reset musi się zakończyć");
    }

    @Test
    void aZeroLengthLootWindowKeepsTheOldImmediateResetBehaviour() throws Exception {
        harness.setConfig(harness.config(0L));

        winTheOutpost();
        harness.tick();
        harness.settle();
        for (int i = 0; i < 10; i++) {
            harness.tick();
            harness.settle();
        }

        assertEquals(RunPhase.COOLDOWN, harness.engine.phase(),
                "loot-seconds: 0 zachowuje natychmiastowy reset dla adminów, którzy tego chcą");
    }

    @Test
    void groundItemsSurviveTheResetUnlessExplicitlyConfigured() throws Exception {
        harness.setConfig(harness.config(0L));
        winTheOutpost();

        // A player death drop inside the fortress must not be swept away as event residue.
        var drop = harness.world.dropItem(harness.inside(9, 64, 9), new ItemStack(Material.DIAMOND, 1));

        harness.tick();
        harness.settle();
        for (int i = 0; i < 10; i++) {
            harness.tick();
            harness.settle();
        }

        assertTrue(drop.isValid() && !drop.isDead(),
                "domyślnie nie usuwamy przedmiotów leżących na ziemi");
    }

    @Test
    void theLootWindowIsPersistedAndRestoredWithTheSameDeadline() throws Exception {
        winTheOutpost();
        harness.tick();
        harness.settle();

        long deadline = harness.engine.state().lootUntilMillis();
        assertTrue(deadline > 0L);

        var stored = harness.persistence.repository().loadAll();
        assertTrue(stored.hasRun());
        assertEquals(RunPhase.LOOTING, stored.run().phase());
        assertEquals(deadline, stored.run().lootUntilMillis(),
                "restart wznawia to samo okno, a nie nowe pełne");
    }

    @Test
    void theConfigUsedByTheTestActuallyDisablesLootFilling() {
        // Guards the guard: these tests assert on containers, so the loot service must not be
        // filling or clearing them behind the scenes.
        assertEquals(LootConfig.disabled().enabled(), harness.config(120L).loot().enabled());
        assertEquals(Map.of(), harness.config(120L).loot().bindings());
    }
}
