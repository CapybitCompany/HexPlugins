package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CustomItemDefinition;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Registriert config-getriebene Crafting-Rezepte und ersetzt sie beim Reload sauber.
 * Alle NamespacedKeys liegen unter der HexCustomItems-Instanz.
 */
public final class RecipeService {

    private final JavaPlugin plugin;
    private final CustomItemRegistryService registryService;
    private final List<NamespacedKey> registeredKeys = new ArrayList<>();

    public RecipeService(JavaPlugin plugin, CustomItemRegistryService registryService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registryService = Objects.requireNonNull(registryService, "registryService");
    }

    /** Entfernt bisherige Rezepte und registriert die aktuell konfigurierten. */
    public void register(HexCustomItemsConfig config) {
        removeAll();

        HexCustomItemsConfig.Recipes recipes = config.recipes();
        if (!recipes.enabled()) {
            return;
        }

        for (Map.Entry<String, HexCustomItemsConfig.RecipeSpec> entry : recipes.items().entrySet()) {
            String itemId = entry.getKey().toLowerCase(Locale.ROOT);
            CustomItemDefinition definition = registryService.findById(itemId);
            if (definition == null) {
                plugin.getLogger().warning("Rezept dla nieznanego przedmiotu '" + itemId + "' - pomijam.");
                continue;
            }

            HexCustomItemsConfig.RecipeSpec spec = entry.getValue();
            NamespacedKey key = new NamespacedKey(plugin, "recipe_" + itemId);
            ItemStack result = registryService.createItem(definition, spec.amount());

            Recipe recipe = spec.shapeless()
                    ? buildShapeless(key, result, spec, itemId)
                    : buildShaped(key, result, spec, itemId);
            if (recipe == null) {
                continue;
            }

            try {
                Bukkit.addRecipe(recipe);
                registeredKeys.add(key);
            } catch (IllegalStateException ex) {
                plugin.getLogger().warning("Nie udało się dodać rezept '" + itemId + "': " + ex.getMessage());
            }
        }
    }

    public void removeAll() {
        for (NamespacedKey key : registeredKeys) {
            Bukkit.removeRecipe(key);
        }
        registeredKeys.clear();
    }

    private Recipe buildShaped(NamespacedKey key, ItemStack result, HexCustomItemsConfig.RecipeSpec spec, String itemId) {
        if (spec.shape().isEmpty()) {
            plugin.getLogger().warning("Rezept '" + itemId + "' nie ma pola 'shape' - pomijam.");
            return null;
        }
        try {
            ShapedRecipe recipe = new ShapedRecipe(key, result);
            recipe.shape(spec.shape().toArray(new String[0]));
            String shapeChars = String.join("", spec.shape());
            for (Map.Entry<String, Material> ingredient : spec.shapedIngredients().entrySet()) {
                if (ingredient.getKey().isEmpty()) {
                    continue;
                }
                char symbol = ingredient.getKey().charAt(0);
                if (shapeChars.indexOf(symbol) < 0) {
                    plugin.getLogger().warning("Rezept '" + itemId + "': symbol '" + symbol + "' nie występuje w shape - pomijam symbol.");
                    continue;
                }
                recipe.setIngredient(symbol, ingredient.getValue());
            }
            return recipe;
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Niepoprawne rezept shaped '" + itemId + "': " + ex.getMessage());
            return null;
        }
    }

    private Recipe buildShapeless(NamespacedKey key, ItemStack result, HexCustomItemsConfig.RecipeSpec spec, String itemId) {
        if (spec.shapelessIngredients().isEmpty()) {
            plugin.getLogger().warning("Rezept shapeless '" + itemId + "' nie ma składników - pomijam.");
            return null;
        }
        try {
            ShapelessRecipe recipe = new ShapelessRecipe(key, result);
            for (Material material : spec.shapelessIngredients()) {
                recipe.addIngredient(material);
            }
            return recipe;
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Niepoprawne rezept shapeless '" + itemId + "': " + ex.getMessage());
            return null;
        }
    }
}
