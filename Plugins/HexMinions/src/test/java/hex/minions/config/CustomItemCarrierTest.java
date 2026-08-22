package hex.minions.config;

import hex.minions.crafting.SpecialItemCarrierResolver;
import hex.minions.diagnostics.CustomItemCarrierPolicy;
import hex.minions.diagnostics.PlaceableItemPolicy;
import hex.minions.energy.CableType;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CustomItemCarrierTest {
    private YamlConfiguration yaml(String name) {
        var stream = getClass().getClassLoader().getResourceAsStream(name);
        assertNotNull(stream, name + " missing from test classpath");
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    @Test void compressionCarriersAreNonBlock() {
        ConfigurationSection root = yaml("resources.yml").getConfigurationSection("resources");
        assertNotNull(root);
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null || !section.getBoolean("compression.enabled", false)) continue;
            Material material = Material.matchMaterial(section.getString("compression.compressed-material", ""));
            assertNotNull(material, id);
            assertFalse(material.isBlock(), id + " -> " + material);
        }
    }

    @Test void resourceCustomModelCarriersUseProductionResolverAndAreNonBlock() {
        ConfigurationSection root = yaml("resources.yml").getConfigurationSection("resources");
        assertNotNull(root);
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null || section.getInt("custom-model-data", 0) <= 0) continue;
            Material material = SpecialItemCarrierResolver.resolveConfiguredCarrier(id, id, root).orElse(null);
            assertNotNull(material, id);
            assertFalse(material.isBlock(), id + " -> " + material);
        }
    }

    @Test void explicitSpecialItemCarriersAreNonBlockIncludingDisabled() {
        ConfigurationSection root = yaml("special-items.yml").getConfigurationSection("special-items");
        assertNotNull(root);
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null || !section.isSet("material")) continue;
            Material material = Material.matchMaterial(section.getString("material", ""));
            assertNotNull(material, id);
            assertFalse(material.isBlock(), id + " -> " + material);
        }
    }

    @Test void resourceBackedSpecialItemsUseSameCanonicalResolverAsProduction() {
        ConfigurationSection special = yaml("special-items.yml").getConfigurationSection("special-items");
        ConfigurationSection resources = yaml("resources.yml").getConfigurationSection("resources");
        assertNotNull(special);
        assertNotNull(resources);
        for (String id : special.getKeys(false)) {
            ConfigurationSection section = special.getConfigurationSection(id);
            if (section == null || section.isSet("material")) continue;
            String resourceRef = section.getString("resource-ref", "").trim();
            if (resourceRef.isBlank()) continue;
            Material material = SpecialItemCarrierResolver.resolveConfiguredCarrier(id, resourceRef, resources).orElse(null);
            assertNotNull(material, id + " resource-ref=" + resourceRef);
            assertFalse(material.isBlock(), id + " -> " + resourceRef + " -> " + material);
        }
    }

    @Test void storageCarriersAndPlacedMaterialsHaveSeparatedRoles() {
        ConfigurationSection root = yaml("storage-chests.yml").getConfigurationSection("storage-chests");
        assertNotNull(root);
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            assertNotNull(section);
            Material carrier = Material.matchMaterial(section.getString("item.material", ""));
            Material placed = Material.matchMaterial(section.getString("item.placed-material", ""));
            assertNotNull(carrier, id);
            assertFalse(carrier.isBlock(), id + " carrier -> " + carrier);
            assertNotNull(placed, id);
            assertNotEquals(Material.AIR, placed, id);
            assertTrue(placed.isBlock(), id + " placed-material -> " + placed);
        }
    }

    @Test void generatedCompressionIdentityKeepsRequiredCarriersAndCmd() {
        ConfigurationSection resources = yaml("resources.yml").getConfigurationSection("resources");
        assertNotNull(resources);
        Map<String, String> expected = Map.of(
                "iron", "IRON_INGOT",
                "gold", "GOLD_INGOT",
                "netherrack", "NETHER_BRICK",
                "cobblestone", "BRICK"
        );
        expected.forEach((id, material) -> assertEquals(material, resources.getString(id + ".compression.compressed-material")));
        assertEquals(14201, resources.getInt("netherrack.compression.compressed.custom-model-data"));
        assertEquals(14202, resources.getInt("netherrack.compression.super.custom-model-data"));
        assertEquals(10001, resources.getInt("compressed_cobblestone.custom-model-data"));

        ConfigurationSection special = yaml("special-items.yml").getConfigurationSection("special-items");
        assertNotNull(special);
        assertFalse(special.isConfigurationSection("compressed_netherrack"));
        assertFalse(special.isConfigurationSection("super_compressed_netherrack"));
    }

    @Test void compressedCobblestoneCarrierIsSingleSource() {
        ConfigurationSection resources = yaml("resources.yml").getConfigurationSection("resources");
        assertNotNull(resources);
        ConfigurationSection legacyAlias = resources.getConfigurationSection("compressed_cobblestone");
        assertNotNull(legacyAlias);
        assertFalse(legacyAlias.isSet("material"), "legacy alias must not carry an independent material");
        assertEquals(10001, legacyAlias.getInt("custom-model-data"));

        resources.set("cobblestone.compression.compressed-material", "FLINT");
        assertEquals(Material.FLINT,
                SpecialItemCarrierResolver.resolveConfiguredCarrier("compressed_cobblestone", "compressed_cobblestone", resources).orElseThrow());
        assertEquals(Material.FLINT,
                SpecialItemCarrierResolver.resolveConfiguredCarrier("super_compressed_cobblestone", "super_compressed_cobblestone", resources).orElseThrow());
        assertEquals(10001, legacyAlias.getInt("custom-model-data"), "carrier changes must not alter legacy CMD");
    }

    @Test void minionPdcItemsAreExplicitlyClassifiedAsVanillaUntilTheyGainCmd() {
        ConfigurationSection root = yaml("minion-types.yml").getConfigurationSection("minion-types");
        assertNotNull(root);
        for (String id : root.getKeys(false)) {
            ConfigurationSection item = root.getConfigurationSection(id + ".item");
            if (item == null) continue;
            int cmd = item.getInt("custom-model-data", 0);
            if (cmd == 0) {
                assertEquals(CustomItemCarrierPolicy.Classification.PLUGIN_IDENTIFIED_VANILLA_ITEM,
                        CustomItemCarrierPolicy.minionItemClassification(cmd), id);
            } else {
                assertEquals(CustomItemCarrierPolicy.Classification.RESOURCE_PACK_CUSTOM_ITEM,
                        CustomItemCarrierPolicy.minionItemClassification(cmd), id);
                Material material = Material.matchMaterial(item.getString("material", ""));
                assertNotNull(material, id);
                assertFalse(material.isBlock(), id);
            }
        }
    }

    @Test void allRawPlaceableSpecialItemsHaveKnownStrategyIncludingHiddenRobotAndCables() {
        YamlConfiguration specialYaml = yaml("special-items.yml");
        ConfigurationSection items = specialYaml.getConfigurationSection("special-items");
        ConfigurationSection stations = specialYaml.getConfigurationSection("crafting-stations");
        ConfigurationSection machines = yaml("machines.yml").getConfigurationSection("machines");
        assertNotNull(items);
        assertNotNull(stations);
        assertNotNull(machines);

        for (String id : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(id);
            if (item == null || !item.getBoolean("placeable", false)) continue;
            String blockKind = item.getString("block-kind", id);
            PlaceableItemPolicy.Strategy strategy = PlaceableItemPolicy.classify(blockKind);
            if (strategy == PlaceableItemPolicy.Strategy.CABLE) {
                assertNotNull(CableType.fromSpecialItem(id), id + " has no CableType placement mapping");
            } else {
                PlaceableItemPolicy.Resolution resolution = PlaceableItemPolicy.resolve(blockKind, stations, machines);
                assertTrue(resolution.supported(), id + " / " + blockKind + " -> " + resolution);
            }
        }

        ConfigurationSection robot = items.getConfigurationSection("miner_robot");
        assertNotNull(robot);
        assertTrue(robot.getBoolean("placeable"));
        assertEquals("ROBOT_MINER", robot.getString("block-kind"));
        PlaceableItemPolicy.Resolution robotResolution = PlaceableItemPolicy.resolve("ROBOT_MINER", stations, machines);
        assertEquals(PlaceableItemPolicy.Strategy.ROBOT, robotResolution.strategy());
        assertEquals(Material.BARREL, robotResolution.physicalBlock());
        assertTrue(robotResolution.supported());
    }
}
