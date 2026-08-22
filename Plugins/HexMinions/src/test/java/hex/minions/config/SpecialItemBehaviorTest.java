package hex.minions.config;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockPlugin;
import be.seeseemelk.mockbukkit.ServerMock;
import hex.minions.crafting.SpecialIngredient;
import hex.minions.crafting.SpecialItemRegistry;
import hex.minions.service.MinionItemFactory;
import hex.minions.service.StorageChestPlacementResolver;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/** Behavioral tests that require Bukkit ItemMeta/PDC rather than source-text inspection. */
class SpecialItemBehaviorTest {
    private ServerMock server;
    private MockPlugin plugin;

    @BeforeEach
    void setUp() throws IOException {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Files.createDirectories(plugin.getDataFolder().toPath());
        copyResource("special-items.yml");
        copyResource("resources.yml");
        copyResource("minion-types.yml");
        copyResource("storage-chests.yml");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void specialItemMatchingIsActuallyPdcFirstAcrossCurrentVanillaAndLegacyCarriers() {
        SpecialItemRegistry registry = SpecialItemRegistry.load(plugin);
        SpecialIngredient ingredient = new SpecialIngredient(Material.IRON_INGOT, 1, 0, "compressed_iron");

        ItemStack current = registry.createItem("compressed_iron", 1);
        assertEquals(Material.IRON_INGOT, current.getType());
        assertEquals("compressed_iron", registry.readSpecialItemId(current).orElseThrow());
        assertTrue(ingredient.matches(current, registry));

        ItemStack vanilla = new ItemStack(Material.IRON_INGOT, 1);
        assertTrue(registry.readSpecialItemId(vanilla).isEmpty());
        assertFalse(ingredient.matches(vanilla, registry), "same Material without special-item PDC must not match");

        ItemMeta legacyMeta = current.getItemMeta();
        assertNotNull(legacyMeta);
        ItemStack legacy = new ItemStack(Material.IRON_BLOCK, 1);
        assertTrue(legacy.setItemMeta(legacyMeta));
        assertEquals("compressed_iron", registry.readSpecialItemId(legacy).orElseThrow());
        assertTrue(ingredient.matches(legacy, registry), "legacy block carrier with valid PDC must remain compatible");
    }


    @Test
    void generatedCompressionRegistryKeepsExpectedCarrierAndCmdIdentity() {
        SpecialItemRegistry registry = SpecialItemRegistry.load(plugin);
        assertCompression(registry, "compressed_iron", Material.IRON_INGOT, 11005);
        assertCompression(registry, "super_compressed_iron", Material.IRON_INGOT, 11006);
        assertCompression(registry, "compressed_gold", Material.GOLD_INGOT, 11019);
        assertCompression(registry, "super_compressed_gold", Material.GOLD_INGOT, 11020);
        assertCompression(registry, "compressed_netherrack", Material.NETHER_BRICK, 14201);
        assertCompression(registry, "super_compressed_netherrack", Material.NETHER_BRICK, 14202);
        assertCompression(registry, "compressed_cobblestone", Material.BRICK, 10001);
        assertCompression(registry, "super_compressed_cobblestone", Material.BRICK, 11002);
    }

    @Test
    void compressedCobblestoneRegistryCarrierChangesFromOneCanonicalSourceAndKeepsCmd() throws IOException {
        var path = plugin.getDataFolder().toPath().resolve("resources.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
        yaml.set("resources.cobblestone.compression.compressed-material", "FLINT");
        yaml.set("resources.compressed_cobblestone.material", null);
        yaml.save(path.toFile());

        SpecialItemRegistry registry = SpecialItemRegistry.load(plugin);
        var compressed = registry.item("compressed_cobblestone").orElseThrow();
        var superCompressed = registry.item("super_compressed_cobblestone").orElseThrow();
        assertEquals(Material.FLINT, compressed.material());
        assertEquals(Material.FLINT, superCompressed.material());
        assertEquals(10001, compressed.customModelData());
    }

    @Test
    void invalidStoragePdcIdResolvesStrictlyWithoutFallbackOrConsumption() {
        StorageChestRegistry registry = StorageChestRegistry.load(plugin);
        MinionItemFactory factory = new MinionItemFactory(plugin);
        StorageChestDefinition small = registry.find("small").orElseThrow();
        ItemStack item = factory.createStorageChestItem(small, 2);

        ItemMeta meta = item.getItemMeta();
        assertNotNull(meta);
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "storage_chest_id"), PersistentDataType.STRING, "missing_storage_id");
        item.setItemMeta(meta);
        int before = item.getAmount();
        var world = server.addSimpleWorld("storage-validation");
        var untouched = world.getBlockAt(4, 70, 4);
        Material beforeWorld = untouched.getType();

        assertTrue(StorageChestPlacementResolver.resolve(item, factory, registry).isEmpty());
        assertEquals(before, item.getAmount(), "strict validation must not consume the item");
        assertEquals(beforeWorld, untouched.getType(), "strict validation must not mutate the world");
    }


    private static void assertCompression(SpecialItemRegistry registry, String id, Material material, int cmd) {
        var item = registry.item(id).orElseThrow();
        assertEquals(material, item.material(), id);
        assertEquals(cmd, item.customModelData(), id);
    }

    private void copyResource(String name) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, name + " missing from test classpath");
            Files.copy(input, plugin.getDataFolder().toPath().resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
