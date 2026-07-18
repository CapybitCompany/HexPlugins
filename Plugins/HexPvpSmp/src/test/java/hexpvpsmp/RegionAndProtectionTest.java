package hexpvpsmp;

import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.config.WorldConfig;
import hexpvpsmp.protection.ConfigRegionProtectionProvider;
import hexpvpsmp.protection.ProtectionService;
import hexpvpsmp.region.Cuboid;
import hexpvpsmp.region.RegionType;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionAndProtectionTest {

    private ServerMock server;
    private HexPvpSmpPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        server.addSimpleWorld("nether");
        plugin = MockBukkit.load(HexPvpSmpPlugin.class);
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void cuboidContainsIsXzOnlyAndIgnoresY() {
        Cuboid c = new Cuboid(-10, -10, 10, 10);
        assertTrue(c.contains(0, 0));
        assertTrue(c.contains(-10, -10));
        assertTrue(c.contains(10, 10));
        assertFalse(c.contains(11, 0));
        assertFalse(c.contains(0, 11));
        // contains(Location) ignores Y: same x/z at wildly different heights.
        assertTrue(c.contains(new Location(server.getWorld("world"), 0, -5000, 0)));
        assertTrue(c.contains(new Location(server.getWorld("world"), 0, 5000, 0)));
    }

    @Test
    void cuboidHorizontalDistanceToEdge() {
        Cuboid c = new Cuboid(0, 0, 100, 100);
        // dead center -> 50 to nearest edge
        assertEquals(50.0, c.horizontalDistanceToEdge(50, 50));
        // near the edge inside
        assertEquals(2.0, c.horizontalDistanceToEdge(2, 50));
        // outside on x
        assertEquals(5.0, c.horizontalDistanceToEdge(-5, 50));
    }

    @Test
    void spawnAndNoBuildProtectionApplyAtEveryHeight() {
        ProtectionService service = plugin.protectionService();
        for (int y : new int[]{-64, -32, 0, 64, 200, 319}) {
            assertTrue(service.isPvpProtected("world", 0, y, 0),
                    "spawn PvP protection must hold at y=" + y);
            assertTrue(service.isBuildProtected("world", 0, y, 0),
                    "spawn build protection must hold at y=" + y);
            // No-build zone: build-protected but PvP allowed, at every height.
            assertFalse(service.isPvpProtected("world", 0, y, 150),
                    "no-build zone must never block PvP, y=" + y);
            assertTrue(service.isBuildProtected("world", 0, y, 150),
                    "no-build zone build protection must hold at y=" + y);
        }
    }

    @Test
    void configProviderHonorsPerWorldRegions() {
        // Default config has spawn enabled in "world" only.
        ConfigRegionProtectionProvider provider = new ConfigRegionProtectionProvider(plugin::config);
        // Inside spawn in "world"
        assertFalse(provider.regionsAt("world", 0, 64, 0).isEmpty(),
                "should hit spawn region in 'world'");
        // Same coordinates in "nether" -> not configured -> no regions
        assertTrue(provider.regionsAt("nether", 0, 64, 0).isEmpty(),
                "should not match in 'nether'");
        // Outside every region in "world"
        assertTrue(provider.regionsAt("world", 9999, 64, 9999).isEmpty());
    }

    @Test
    void spawnRegionIsSafezoneNoBuildZoneIsNotPvpProtected() {
        ProtectionService service = plugin.protectionService();
        // Spawn (inside): PvP-protected and build-protected.
        assertTrue(service.isPvpProtected("world", 0, 64, 0));
        assertTrue(service.isBuildProtected("world", 0, 64, 0));
        // No-build zone (front_spawn, z in [101,180]): build-protected but PvP allowed.
        assertFalse(service.isPvpProtected("world", 0, 64, 150));
        assertTrue(service.isBuildProtected("world", 0, 64, 150));
        // Wilderness: neither.
        assertFalse(service.isPvpProtected("world", 9999, 64, 9999));
        assertFalse(service.isBuildProtected("world", 9999, 64, 9999));
    }

    @Test
    void regionsCarryCorrectTypes() {
        ProtectionService service = plugin.protectionService();
        boolean spawnIsSafezone = service.regionsAt("world", 0, 64, 0).stream()
                .anyMatch(r -> r.type() == RegionType.SPAWN_SAFEZONE);
        assertTrue(spawnIsSafezone);
        boolean frontIsNoBuild = service.regionsAt("world", 0, 64, 150).stream()
                .anyMatch(r -> r.type() == RegionType.NO_BUILD);
        assertTrue(frontIsNoBuild);
    }

    @Test
    void worldsAreNormalizedLowercase() {
        HexPvpConfig config = plugin.config();
        WorldConfig wc = config.world("WORLD").orElseThrow();
        assertEquals("world", wc.world());
    }

    @Test
    void allRegionsReturnsSpawnAndNoBuildZones() {
        List<?> all = plugin.protectionService().allRegions();
        // 1 spawn + 4 no-build ring zones (north/south/east/west) == 5
        assertEquals(5, all.size());
    }

    @Test
    void multipleNoBuildRingZonesAllProtect() {
        ProtectionService service = plugin.protectionService();
        // One representative point inside each of the four ring zones.
        assertTrue(service.isBuildProtected("world", 0, 64, -150), "north ring builds blocked");
        assertTrue(service.isBuildProtected("world", 0, 64, 150), "south ring builds blocked");
        assertTrue(service.isBuildProtected("world", 150, 64, 0), "east ring builds blocked");
        assertTrue(service.isBuildProtected("world", -150, 64, 0), "west ring builds blocked");
        // None of the ring zones block PvP (they are NO_BUILD, not safezones).
        assertFalse(service.isPvpProtected("world", 0, 64, -150));
        assertFalse(service.isPvpProtected("world", 150, 64, 0));
        // The corner gap outside the ring is wilderness.
        assertFalse(service.isBuildProtected("world", 500, 64, 500));
    }

    @Test
    void publicChestIsRegistered() {
        assertTrue(plugin.publicChestRegistry().isPublicChest("world", 0, 65, 0));
        assertFalse(plugin.publicChestRegistry().isPublicChest("world", 1, 65, 0));
    }

    @Test
    void disabledWorldContributesNoRegions() {
        plugin.getConfig().set("worlds.world.enabled", false);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        ProtectionService service = plugin.protectionService();
        assertTrue(service.allRegions().isEmpty(),
                "a disabled world must not contribute regions to /hexpvp regions");
        assertTrue(service.regionsAt("world", 0, 64, 0).isEmpty(),
                "a disabled world must not protect any location");
    }

    @Test
    void publicChestIsIgnoredWhenWorldDisabled() {
        // Enabled: the configured chest is recognised.
        assertTrue(plugin.publicChestRegistry().isPublicChest("world", 0, 65, 0));

        plugin.getConfig().set("worlds.world.enabled", false);
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        assertFalse(plugin.publicChestRegistry().isPublicChest("world", 0, 65, 0),
                "a public chest in a disabled world must not be treated as special");
    }
}
