package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

/**
 * Kept as a compatibility wrapper for older tests/call sites.
 * Real recipes now use CustomItemsCraftingListener because Bukkit recipes cannot require
 * multiple items in a single crafting slot.
 */
public final class RecipeService {

    public RecipeService(JavaPlugin plugin, CustomItemRegistryService registryService) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(registryService, "registryService");
    }

    public void register(HexCustomItemsConfig config) {
        Objects.requireNonNull(config, "config");
    }

    public void removeAll() {
    }
}
