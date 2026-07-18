package hexpvpsmp;

import hexpvpsmp.protection.ProtectionService;
import hexpvpsmp.region.RegionType;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Region priority: spawn safezone must win over an overlapping no-build zone. */
class RegionPriorityTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Location loc(int x, int z) {
        return new Location(server.getWorld("world"), x, 64, z);
    }

    @Test
    void spawnIsSpawnType() {
        ProtectionService service = plugin.protectionService();
        assertEquals(RegionType.SPAWN_SAFEZONE, service.effectiveType(loc(0, 0)).orElseThrow());
        assertTrue(service.isSpawnSafezone(loc(0, 0)));
    }

    @Test
    void noBuildIsNoBuildType() {
        ProtectionService service = plugin.protectionService();
        assertEquals(RegionType.NO_BUILD, service.effectiveType(loc(0, 150)).orElseThrow());
        assertFalse(service.isSpawnSafezone(loc(0, 150)));
    }

    @Test
    void wildernessHasNoType() {
        ProtectionService service = plugin.protectionService();
        assertTrue(service.effectiveType(loc(9999, 9999)).isEmpty());
    }

    @Test
    void overlappingSpawnWinsOverNoBuild() {
        // Extend a no-build zone to overlap the spawn box (which covers -100..100).
        plugin.getConfig().set("worlds.world.no-build-zones.overlap.enabled", true);
        plugin.getConfig().set("worlds.world.no-build-zones.overlap.region.min-x", -50);
        plugin.getConfig().set("worlds.world.no-build-zones.overlap.region.max-x", 50);
        plugin.getConfig().set("worlds.world.no-build-zones.overlap.region.min-z", -50);
        plugin.getConfig().set("worlds.world.no-build-zones.overlap.region.max-z", 50);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        ProtectionService service = plugin.protectionService();
        Location shared = loc(0, 0); // inside both spawn and the new no-build zone
        assertEquals(RegionType.SPAWN_SAFEZONE, service.effectiveType(shared).orElseThrow(),
                "spawn must win over no-build where they overlap");
        assertTrue(service.isPvpProtected(shared), "overlap still blocks PvP (spawn wins)");
        assertTrue(service.isBuildProtected(shared), "overlap still blocks building");
    }

    @Test
    void adjacentRegionsKeepTheirOwnRules() {
        ProtectionService service = plugin.protectionService();
        // Spawn edge (z=100) is spawn; the no-build zone starts at z=101.
        assertEquals(RegionType.SPAWN_SAFEZONE, service.effectiveType(loc(0, 100)).orElseThrow());
        assertEquals(RegionType.NO_BUILD, service.effectiveType(loc(0, 101)).orElseThrow());
        // Spawn blocks PvP, adjacent no-build allows it.
        assertTrue(service.isPvpProtected(loc(0, 100)));
        assertFalse(service.isPvpProtected(loc(0, 101)));
    }
}
