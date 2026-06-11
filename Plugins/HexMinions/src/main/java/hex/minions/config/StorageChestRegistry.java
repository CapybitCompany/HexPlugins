package hex.minions.config;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.Plugin;

import hex.minions.service.MinionItemFactory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class StorageChestRegistry {
    private final Map<String, StorageChestDefinition> definitions;
    private final boolean requireSpecialItem;
    private final boolean allowPlainChestFallback;

    private StorageChestRegistry(Map<String, StorageChestDefinition> definitions, boolean requireSpecialItem, boolean allowPlainChestFallback) {
        this.definitions = Map.copyOf(definitions);
        this.requireSpecialItem = requireSpecialItem;
        this.allowPlainChestFallback = allowPlainChestFallback;
    }

    public static StorageChestRegistry load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "storage-chests.yml");
        if (!file.exists()) plugin.saveResource("storage-chests.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, StorageChestDefinition> definitions = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("storage-chests");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) continue;
                StorageChestDefinition definition = StorageChestDefinition.fromConfig(id, section);
                if (definition.enabled()) definitions.put(definition.id(), definition);
            }
        }
        boolean requireSpecialItem = yaml.getBoolean("settings.require-special-item", true);
        boolean allowPlainChestFallback = yaml.getBoolean("settings.allow-plain-chest-fallback", false);
        return new StorageChestRegistry(definitions, requireSpecialItem, allowPlainChestFallback);
    }

    public Map<String, StorageChestDefinition> definitions() {
        return definitions;
    }

    public Optional<StorageChestDefinition> find(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return Optional.ofNullable(definitions.get(id.toLowerCase(java.util.Locale.ROOT)));
    }

    public Optional<StorageChestDefinition> first() {
        return definitions.values().stream().findFirst();
    }

    public boolean requireSpecialItem() {
        return requireSpecialItem;
    }

    public boolean allowPlainChestFallback() {
        return allowPlainChestFallback;
    }

    public void registerRecipes(Plugin plugin, MinionItemFactory itemFactory) {
        for (StorageChestDefinition definition : definitions.values()) {
            if (!definition.shapedRecipeEnabled()) continue;
            if (definition.recipeShape().isEmpty() || definition.recipeIngredients().isEmpty()) continue;
            try {
                NamespacedKey key = new NamespacedKey(plugin, "storage_chest_" + definition.id());
                Bukkit.removeRecipe(key);
                ShapedRecipe recipe = new ShapedRecipe(key, itemFactory.createStorageChestItem(definition, 1));
                recipe.shape(definition.recipeShape().toArray(String[]::new));
                for (Map.Entry<Character, Material> entry : definition.recipeIngredients().entrySet()) {
                    recipe.setIngredient(entry.getKey(), entry.getValue());
                }
                Bukkit.addRecipe(recipe);
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Nie udało się zarejestrować craftingu Minion Storage '" + definition.id() + "': " + throwable.getMessage());
            }
        }
    }
}
