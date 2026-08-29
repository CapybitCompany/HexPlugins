package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.support.PluginTestBase;
import hexcustomitems.support.TestConfig;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomItemRegistryServiceTest extends PluginTestBase {

    private CustomItemRegistryService registry;
    private NamespacedKey idKey;
    private NamespacedKey chargesKey;

    @BeforeEach
    void setUp() {
        Map<String, CustomItemDefinition> items = new LinkedHashMap<>();
        items.put("jump_potion", TestConfig.selfPotionItem("jump_potion", Material.POTION, "jump_boost", 5, 0));
        items.put("cookie", TestConfig.selfPotionItem("cookie", Material.COOKIE, "invisibility", 15, 3));
        items.put("ancient_scale", new CustomItemDefinition("ancient_scale", "hex:ancient_scale", 10005,
                Material.DISC_FRAGMENT_5, "<white>Scale", List.of("<gray>Crafting material"),
                true, true, false, null, 0, 64, 0, List.of()));
        HexCustomItemsConfig config = TestConfig.withItems(items);
        registry = new CustomItemRegistryService(plugin, config);
        idKey = new NamespacedKey(plugin, "hexcustomitem_id");
        chargesKey = new NamespacedKey(plugin, "hexcustomitem_charges");
    }

    @Test
    void createItemSetsIdInPdc() {
        ItemStack item = registry.createItem(registry.findById("jump_potion"), 1);
        ItemMeta meta = item.getItemMeta();
        assertEquals("jump_potion", meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING));
    }

    @Test
    void resolveItemIdUsesOnlyPdcNotNameMatching() {
        ItemStack managed = registry.createItem(registry.findById("jump_potion"), 1);
        assertEquals("jump_potion", registry.resolveItemId(managed));

        // Gleiche Material + gleicher Anzeigename, aber kein PDC -> darf NICHT erkannt werden.
        ItemStack lookalike = new ItemStack(Material.POTION);
        ItemMeta meta = lookalike.getItemMeta();
        meta.displayName(managed.getItemMeta().displayName());
        lookalike.setItemMeta(meta);
        assertNull(registry.resolveItemId(lookalike));
    }

    @Test
    void chargesAreInitiallySet() {
        ItemStack cookie = registry.createItem(registry.findById("cookie"), 1);
        assertEquals(3, registry.readCharges(cookie));
        assertEquals(3, cookie.getItemMeta().getPersistentDataContainer().get(chargesKey, PersistentDataType.INTEGER));
    }

    @Test
    void writeChargesUpdatesPdcAndLorePlaceholders() {
        CustomItemDefinition cookie = registry.findById("cookie");
        ItemStack item = registry.createItem(cookie, 1);

        registry.writeCharges(item, cookie, 1, null);

        assertEquals(1, registry.readCharges(item));
        String lore = TestConfig.plain(item.getItemMeta().lore().get(0));
        assertTrue(lore.contains("1/3"), "Lore sollte 1/3 zeigen, war: " + lore);
    }

    @Test
    void rendersWithoutPlaceholderApiWithoutError() {
        Player player = server.addPlayer();
        ItemStack item = registry.createItem(registry.findById("jump_potion"), 1, player);
        assertEquals("jump_potion", TestConfig.plain(item.getItemMeta().displayName()));
    }

    @Test
    void createItemForChargedItemForcesSingleStack() {
        ItemStack cookie = registry.createItem(registry.findById("cookie"), 10);
        assertEquals(1, cookie.getAmount());
    }

    @Test
    void discFragmentCustomItemsHideAdditionalVanillaTooltip() {
        ItemStack scale = registry.createItem(registry.findById("ancient_scale"), 1);
        ItemMeta meta = scale.getItemMeta();

        assertTrue(meta.hasItemFlag(ItemFlag.HIDE_ADDITIONAL_TOOLTIP));
    }
}
