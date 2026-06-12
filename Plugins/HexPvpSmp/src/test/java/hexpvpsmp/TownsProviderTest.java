package hexpvpsmp;

import hexpvpsmp.protection.ConfigRegionProtectionProvider;
import hexpvpsmp.protection.ProtectionService;
import hexpvpsmp.region.ProtectedRegion;
import hexpvpsmp.region.RegionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownsProviderTest {

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

    @Test
    void configProviderIncludesConfigTownsWhenProviderIsConfig() {
        // Default config has provider: CONFIG.
        ConfigRegionProtectionProvider provider = new ConfigRegionProtectionProvider(plugin::config);
        List<ProtectedRegion> at = provider.regionsAt("world", 250, 64, 250);
        boolean hasTown = at.stream().anyMatch(r -> r.type() == RegionType.TOWN);
        assertTrue(hasTown, "CONFIG provider must serve config towns");
    }

    @Test
    void configProviderHidesConfigTownsWhenProviderIsWorldGuard() {
        plugin.getConfig().set("towns.provider", "WORLDGUARD");
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        ConfigRegionProtectionProvider provider = new ConfigRegionProtectionProvider(plugin::config);
        List<ProtectedRegion> at = provider.regionsAt("world", 250, 64, 250);
        boolean hasTown = at.stream().anyMatch(r -> r.type() == RegionType.TOWN);
        assertFalse(hasTown,
                "ConfigRegionProtectionProvider must NOT serve config towns when provider != CONFIG");

        // Spawn regions are independent of towns.provider:
        boolean hasSpawn = provider.regionsAt("world", 0, 64, 0).stream()
                .anyMatch(r -> r.type() == RegionType.SPAWN);
        assertTrue(hasSpawn, "spawn region must remain regardless of towns.provider");
    }

    @Test
    void allRegionsRespectsProvider() {
        plugin.getConfig().set("towns.provider", "WORLDGUARD");
        plugin.saveConfig();
        assertTrue(plugin.reloadPluginRuntime());

        ProtectionService service = plugin.protectionService();
        // Spawn only, no town.
        long townCount = service.allRegions().stream()
                .filter(r -> r.type() == RegionType.TOWN).count();
        long spawnCount = service.allRegions().stream()
                .filter(r -> r.type() == RegionType.SPAWN).count();
        assertTrue(townCount == 0, "allRegions must omit config towns when provider != CONFIG");
        assertTrue(spawnCount >= 1, "allRegions must keep spawn regions");
    }
}
