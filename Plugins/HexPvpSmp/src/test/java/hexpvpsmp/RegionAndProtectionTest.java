package hexpvpsmp;

import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.config.WorldConfig;
import hexpvpsmp.protection.ConfigRegionProtectionProvider;
import hexpvpsmp.protection.ProtectionService;
import hexpvpsmp.region.Cuboid;
import hexpvpsmp.region.RegionType;
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
    void cuboidContainsRespectsAllAxes() {
        Cuboid c = new Cuboid(-10, 0, -10, 10, 100, 10);
        assertTrue(c.contains(0, 50, 0));
        assertTrue(c.contains(-10, 0, -10));
        assertTrue(c.contains(10, 100, 10));
        assertFalse(c.contains(11, 50, 0));
        assertFalse(c.contains(0, -1, 0));
        assertFalse(c.contains(0, 50, 11));
    }

    @Test
    void cuboidHorizontalDistanceToEdge() {
        Cuboid c = new Cuboid(0, 0, 0, 100, 100, 100);
        // dead center -> 50 to nearest edge
        assertEquals(50.0, c.horizontalDistanceToEdge(50, 50));
        // near the edge inside
        assertEquals(2.0, c.horizontalDistanceToEdge(2, 50));
        // outside on x
        assertEquals(5.0, c.horizontalDistanceToEdge(-5, 50));
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
        // Outside spawn in "world"
        assertTrue(provider.regionsAt("world", 9999, 64, 9999).isEmpty());
    }

    @Test
    void protectionServiceComposesProviders() {
        ProtectionService service = plugin.protectionService();
        assertFalse(service.regionsAt("world", 0, 64, 0).isEmpty(),
                "spawn should match through service");
        assertTrue(service.regionsAt("world", 9999, 64, 9999).isEmpty());
        // Example town from default config:
        assertFalse(service.regionsAt("world", 250, 64, 250).isEmpty(),
                "example_town should match");
    }

    @Test
    void townRegionsAreLoadedAsTown() {
        HexPvpConfig config = plugin.config();
        assertEquals(1, config.towns().regions().size());
        assertEquals(RegionType.TOWN, config.towns().regions().get(0).type());
    }

    @Test
    void worldsAreNormalizedLowercase() {
        HexPvpConfig config = plugin.config();
        WorldConfig wc = config.world("WORLD").orElseThrow();
        assertEquals("world", wc.world());
    }

    @Test
    void allRegionsReturnsSpawnAndTownsAcrossProviders() {
        List<?> all = plugin.protectionService().allRegions();
        // 1 spawn + 1 example_town == 2
        assertEquals(2, all.size());
    }
}
