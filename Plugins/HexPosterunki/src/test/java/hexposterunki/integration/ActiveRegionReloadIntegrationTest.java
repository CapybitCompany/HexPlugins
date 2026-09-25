package hexposterunki.integration;

import hexposterunki.config.BlockVec;
import hexposterunki.config.Cuboid;
import hexposterunki.config.OutpostCatalog;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.PointDef;
import hexposterunki.config.ValidationReport;
import hexposterunki.domain.RunPhase;
import hexposterunki.engine.LootService;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review finding 5: a reload that moves or removes the running outpost must not strip protection
 * from the place where the fight still happens.
 *
 * <p>The catalog is switched exactly the way {@code HexPosterunkiPlugin} applies a reload (catalog
 * reference plus protection index). Block and container access are judged by the real, registered
 * {@code RegionProtectionListener}.
 */
class ActiveRegionReloadIntegrationTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private OutpostTestHarness harness;
    private Player member;

    @BeforeEach
    void setUp() throws Exception {
        harness = new OutpostTestHarness();
        member = harness.server.addPlayer("Obronca");
        harness.towns.assign(member.getUniqueId(), TOWN, "Rycerze");
        member.teleport(harness.inside(8, 64, 8));
        harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test");
        harness.tick();
        harness.tick();
        harness.settle();
        assertEquals(RunPhase.WAVES, harness.engine.phase());
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    /** The same outpost id, moved right next to its old position (x 32..63 instead of 0..31). */
    private OutpostDefinition moved() {
        return new OutpostDefinition("fort", "Fort Północny", OutpostTestHarness.WORLD,
                Cuboid.of(OutpostTestHarness.WORLD, new BlockVec(32, 60, 0), new BlockVec(63, 80, 31)),
                new PointDef(48, 64, 16, 0, 0), new PointDef(48, 64, 16, 0, 0),
                Map.of("brama", new PointDef(36, 64, 4, 0, 0)),
                Map.of("skrzynia", new BlockVec(42, 64, 10)), 10);
    }

    private static OutpostCatalog catalogOf(OutpostDefinition... definitions) {
        Map<String, OutpostDefinition> map = new java.util.LinkedHashMap<>();
        for (OutpostDefinition definition : definitions) {
            map.put(definition.id(), definition);
        }
        return new OutpostCatalog(map, new ValidationReport());
    }

    private boolean breakIsCancelled(Player player, int x, int y, int z) {
        Block block = harness.inside(x, y, z).getBlock();
        block.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        harness.server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private Inventory hopperAt(int x, int y, int z) {
        Location location = harness.inside(x, y, z);
        location.getBlock().setType(Material.HOPPER);
        return ((org.bukkit.block.Container) location.getBlock().getState()).getInventory();
    }

    private boolean opens(Player player, Inventory inventory) {
        OpenVerdict verdict = new OpenVerdict();
        harness.server.getPluginManager().registerEvents(verdict, harness.plugin);
        try {
            player.openInventory(inventory);
        } finally {
            InventoryOpenEvent.getHandlerList().unregister(verdict);
            player.closeInventory();
        }
        assertTrue(verdict.seen, "zdarzenie otwarcia musi dotrzeć do listenerów");
        return !verdict.cancelled;
    }

    private static final class OpenVerdict implements Listener {
        private boolean seen;
        private boolean cancelled;

        @EventHandler(priority = EventPriority.MONITOR)
        public void onOpen(InventoryOpenEvent event) {
            seen = true;
            cancelled = event.isCancelled();
        }
    }

    private void finishTheRound() throws Exception {
        assertTrue(harness.engine.adminStop());
        for (int i = 0; i < 200 && harness.engine.resetInProgress(); i++) {
            harness.server.getScheduler().performOneTick();
            Thread.sleep(5L);
        }
        harness.settle();
        assertEquals(RunPhase.COOLDOWN, harness.engine.phase());
    }

    @Test
    void movingTheRunningOutpostKeepsItsFightingGroundProtected() {
        harness.applyCatalog(catalogOf(moved()));

        assertTrue(harness.engine.activeRegionContains(harness.inside(8, 64, 8)), "walka trwa w starym miejscu");
        assertTrue(breakIsCancelled(member, 8, 64, 9), "stary teren walki musi pozostać chroniony");
        assertTrue(breakIsCancelled(member, 40, 64, 9), "nowa lokalizacja z konfiguracji też jest chroniona");
        assertTrue(harness.regions.crossesBoundary(harness.inside(31, 64, 5), harness.inside(32, 64, 5)),
                "stara i nowa geometria to różne regiony mimo tego samego id");
    }

    @Test
    void containerAccessStaysBoundToTheGeometryTheRunStartedWith() {
        Inventory oldChest = hopperAt(10, 64, 10);
        Inventory newChest = hopperAt(42, 64, 10);
        harness.loot.markUnlocked(LootService.containerId("fort", "skrzynia"));
        Player foreigner = harness.server.addPlayer("Obcy");
        harness.towns.assign(foreigner.getUniqueId(), OTHER_TOWN, "Najeźdźcy");

        assertTrue(opens(member, oldChest), "przed przeładowaniem drużyna ma dostęp do swojej skrzyni");

        harness.applyCatalog(catalogOf(moved()));

        assertTrue(opens(member, oldChest), "skrzynia trwającej walki nadal należy do drużyny");
        assertFalse(opens(foreigner, oldChest), "obca drużyna nadal nie ma dostępu");
        assertFalse(opens(member, newChest),
                "skrzynia w nowej lokalizacji nie przejmuje odblokowania trwającej rundy");
    }

    @Test
    void removingTheRunningOutpostKeepsItProtectedUntilTheResetFinished() throws Exception {
        harness.applyCatalog(OutpostCatalog.empty());

        assertTrue(breakIsCancelled(member, 8, 64, 9), "usunięty z konfiguracji, ale walka nadal tu trwa");
        assertEquals(RunPhase.WAVES, harness.engine.phase());

        assertTrue(harness.engine.adminStop());
        assertTrue(harness.engine.resetInProgress());
        assertTrue(breakIsCancelled(member, 8, 64, 9), "ochrona obowiązuje także podczas resetu");

        for (int i = 0; i < 200 && harness.engine.resetInProgress(); i++) {
            harness.server.getScheduler().performOneTick();
            Thread.sleep(5L);
        }
        harness.settle();
        assertEquals(RunPhase.COOLDOWN, harness.engine.phase());
        assertFalse(breakIsCancelled(member, 8, 64, 9), "po resecie obowiązuje wyłącznie nowa konfiguracja");
    }

    @Test
    void afterTheRoundOnlyTheMovedConfigurationIsProtected() throws Exception {
        harness.applyCatalog(catalogOf(moved()));
        finishTheRound();

        assertTrue(harness.regions.active().isEmpty());
        assertFalse(breakIsCancelled(member, 8, 64, 9), "stara lokalizacja nie jest już posterunkiem");
        assertTrue(breakIsCancelled(member, 40, 64, 9), "nowa lokalizacja jest chroniona");
        assertTrue(opens(member, hopperAt(10, 64, 10)), "stara skrzynia jest zwykłym blokiem poza regionem");
        assertFalse(opens(member, hopperAt(42, 64, 10)), "nowa skrzynia jest zamknięta poza rundą");
    }
}
