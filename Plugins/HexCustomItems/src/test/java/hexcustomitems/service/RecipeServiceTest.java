package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.support.PluginTestBase;
import hexcustomitems.support.TestConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecipeServiceTest extends PluginTestBase {

    private RecipeService recipeService;

    private NamespacedKey key(String itemId) {
        return new NamespacedKey(plugin, "recipe_" + itemId);
    }

    @BeforeEach
    void setUp() {
        Map<String, CustomItemDefinition> items = new LinkedHashMap<>();
        items.put("test_shaped", TestConfig.commandItem("test_shaped", Material.STICK,
                hexcustomitems.model.CommandExecutorType.CONSOLE, List.of("say a"), false));
        items.put("test_shapeless", TestConfig.commandItem("test_shapeless", Material.STICK,
                hexcustomitems.model.CommandExecutorType.CONSOLE, List.of("say b"), false));
        items.put("test_invalid", TestConfig.commandItem("test_invalid", Material.STICK,
                hexcustomitems.model.CommandExecutorType.CONSOLE, List.of("say c"), false));

        Map<String, HexCustomItemsConfig.RecipeSpec> specs = new LinkedHashMap<>();
        specs.put("test_shaped", new HexCustomItemsConfig.RecipeSpec(
                "shaped", List.of("A", "A"), Map.of("A", Material.STICK), List.of(), 1));
        specs.put("test_shapeless", new HexCustomItemsConfig.RecipeSpec(
                "shapeless", List.of(), Map.of(), List.of(Material.STICK, Material.STONE), 1));
        specs.put("ghost", new HexCustomItemsConfig.RecipeSpec(
                "shaped", List.of("G"), Map.of("G", Material.STICK), List.of(), 1)); // unbekanntes Item
        specs.put("test_invalid", new HexCustomItemsConfig.RecipeSpec(
                "shaped", List.of(), Map.of(), List.of(), 1)); // leeres shape -> ungültig

        HexCustomItemsConfig config = TestConfig.withItems(items,
                new HexCustomItemsConfig.Recipes(true, specs), "true");
        CustomItemRegistryService registry = new CustomItemRegistryService(plugin, config);
        recipeService = new RecipeService(plugin, registry);
        recipeService.register(config);
    }

    @Test
    void shapedAndShapelessRecipesAreRegistered() {
        assertInstanceOf(ShapedRecipe.class, Bukkit.getRecipe(key("test_shaped")));
        assertInstanceOf(ShapelessRecipe.class, Bukkit.getRecipe(key("test_shapeless")));
    }

    @Test
    void unknownItemRecipeIsIgnored() {
        assertNull(Bukkit.getRecipe(key("ghost")));
    }

    @Test
    void invalidRecipeIsNotRegistered() {
        assertNull(Bukkit.getRecipe(key("test_invalid")));
    }

    @Test
    void removeAllRemovesRegisteredRecipes() {
        recipeService.removeAll();
        assertNull(Bukkit.getRecipe(key("test_shaped")));
        assertNull(Bukkit.getRecipe(key("test_shapeless")));
    }
}
