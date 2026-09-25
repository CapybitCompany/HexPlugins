package hexposterunki.integration;

import hexposterunki.config.OutpostCatalog;
import hexposterunki.domain.RunPhase;
import hexposterunki.listener.RegionProtectionListener;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full review, finding 2: changes that start outside a protected region but reach into it - the second
 * half of a bed, a grown tree, bone meal - are refused through the real Bukkit event bus. The region of
 * the test fortress is x 0..31; x = -1 lies just outside. Protection applies without a running event
 * and keeps covering the pinned geometry of a run whose outpost was removed from the catalog.
 */
class BorderCrossingProtectionIntegrationTest {

    private OutpostTestHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = new OutpostTestHarness();
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    private BlockState changed(int x, int y, int z, Material material) {
        BlockState state = harness.world.getBlockAt(x, y, z).getState();
        state.setType(material);
        return state;
    }

    private boolean bedIsCancelled(Player player, int footX, int headX) {
        Block foot = harness.world.getBlockAt(footX, 64, 10);
        Block head = harness.world.getBlockAt(headX, 64, 10);
        BlockMultiPlaceEvent event = new BlockMultiPlaceEvent(List.of(foot.getState(), head.getState()),
                foot.getRelative(0, -1, 0), new ItemStack(Material.WHITE_BED), player, true);
        harness.server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean treeIsPrevented(Player player, int originX, BlockState... blocks) {
        StructureGrowEvent event = new StructureGrowEvent(harness.inside(originX, 64, 10), TreeType.TREE, true,
                player, new ArrayList<>(List.of(blocks)));
        harness.server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    private boolean boneMealIsPrevented(Player player, int originX, BlockState... blocks) {
        BlockFertilizeEvent event = new BlockFertilizeEvent(harness.world.getBlockAt(originX, 64, 10), player,
                new ArrayList<>(List.of(blocks)));
        harness.server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void aBedWhoseSecondHalfWouldLieInsideIsRefusedWithoutARunningEvent() {
        Player player = harness.server.addPlayer();
        assertEquals(RunPhase.COOLDOWN, harness.engine.phase(), "brak aktywnego eventu");

        assertTrue(bedIsCancelled(player, -1, 0), "druga połowa łóżka trafiłaby na teren posterunku");
        assertTrue(bedIsCancelled(player, 0, -1), "łóżko postawione od środka też jest blokowane");
        assertFalse(bedIsCancelled(player, -3, -2), "łóżko w całości poza regionem jest dozwolone");

        player.addAttachment(harness.plugin, RegionProtectionListener.BYPASS, true);
        assertFalse(bedIsCancelled(player, -1, 0), "administrator z uprawnieniem obejścia może budować");
    }

    @Test
    void aTreeGrowingAcrossTheBorderIsRefused() {
        assertTrue(treeIsPrevented(null, -1, changed(-1, 64, 10, Material.OAK_LOG), changed(0, 65, 10, Material.OAK_LEAVES)),
                "naturalny wzrost drzewa nie może zmienić bloków posterunku");
        assertTrue(treeIsPrevented(harness.server.addPlayer(), -1, changed(0, 64, 10, Material.OAK_LOG)),
                "drzewo z mączki kostnej gracza też nie");
        assertFalse(treeIsPrevented(null, -5, changed(-5, 64, 10, Material.OAK_LOG), changed(-4, 65, 10, Material.OAK_LEAVES)),
                "drzewo w całości poza regionem rośnie normalnie");

        Player admin = harness.server.addPlayer();
        admin.addAttachment(harness.plugin, RegionProtectionListener.BYPASS, true);
        assertFalse(treeIsPrevented(admin, -1, changed(0, 64, 10, Material.OAK_LOG)));
    }

    @Test
    void boneMealReachingAcrossTheBorderIsRefused() {
        Player player = harness.server.addPlayer();
        assertTrue(boneMealIsPrevented(player, -1, changed(-1, 64, 10, Material.MOSS_BLOCK), changed(0, 64, 10, Material.MOSS_BLOCK)),
                "mączka kostna z zewnątrz nie może zmienić bloków posterunku");
        assertTrue(boneMealIsPrevented(player, 5, changed(5, 64, 10, Material.MOSS_BLOCK)),
                "nawożenie wewnątrz regionu jest blokowane");
        assertFalse(boneMealIsPrevented(player, -5, changed(-5, 64, 10, Material.MOSS_BLOCK), changed(-6, 64, 10, Material.MOSS_BLOCK)),
                "nawożenie poza regionem jest dozwolone");

        player.addAttachment(harness.plugin, RegionProtectionListener.BYPASS, true);
        assertFalse(boneMealIsPrevented(player, -1, changed(0, 64, 10, Material.MOSS_BLOCK)));
    }

    @Test
    void thePinnedGeometryOfARunningOutpostStaysProtectedAfterItLeftTheCatalog() throws Exception {
        Player member = harness.server.addPlayer("Obronca");
        harness.towns.assign(member.getUniqueId(), UUID.randomUUID(), "Rycerze");
        member.teleport(harness.inside(8, 64, 8));
        assertTrue(harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test"));
        harness.tick();
        harness.tick();
        harness.settle();
        harness.applyCatalog(OutpostCatalog.empty());

        Player player = harness.server.addPlayer();
        assertTrue(bedIsCancelled(player, -1, 0), "zamrożona geometria trwającego runu");
        assertTrue(treeIsPrevented(null, -1, changed(0, 64, 10, Material.OAK_LOG)));
        assertTrue(boneMealIsPrevented(player, -1, changed(0, 64, 10, Material.MOSS_BLOCK)));
        assertFalse(bedIsCancelled(player, -3, -2));
    }
}
