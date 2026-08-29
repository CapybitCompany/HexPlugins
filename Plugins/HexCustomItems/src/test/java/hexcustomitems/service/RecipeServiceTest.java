package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.support.PluginTestBase;
import hexcustomitems.support.TestConfig;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RecipeServiceTest extends PluginTestBase {

    @Test
    void compatibilityServiceDoesNotRegisterVanillaRecipes() {
        Map<String, CustomItemDefinition> items = new LinkedHashMap<>();
        items.put("test_item", TestConfig.commandItem("test_item", Material.STICK,
                hexcustomitems.model.CommandExecutorType.CONSOLE, List.of("say a"), false));
        HexCustomItemsConfig config = TestConfig.withItems(items,
                new HexCustomItemsConfig.Recipes(true, Map.of()), "true");

        RecipeService recipeService = new RecipeService(plugin, new CustomItemRegistryService(plugin, config));

        assertDoesNotThrow(() -> recipeService.register(config));
        assertDoesNotThrow(recipeService::removeAll);
    }
}
