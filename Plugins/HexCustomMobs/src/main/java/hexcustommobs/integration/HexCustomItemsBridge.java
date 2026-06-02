package hexcustommobs.integration;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.logging.Logger;

public final class HexCustomItemsBridge {

    private static final String PLUGIN_NAME = "HexCustomItems";

    private final Logger logger;

    private Plugin cachedPlugin;
    private Object cachedRegistryService;
    private Method findByIdMethod;
    private Method createItemMethod;

    public HexCustomItemsBridge(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public boolean isAvailable() {
        return resolveRegistry() != null;
    }

    public ItemStack createItemById(String customItemId, int amount) {
        if (customItemId == null || customItemId.isBlank()) {
            return null;
        }
        Object registry = resolveRegistry();
        if (registry == null || findByIdMethod == null || createItemMethod == null) {
            return null;
        }
        try {
            Object definition = findByIdMethod.invoke(registry, customItemId);
            if (definition == null) {
                return null;
            }
            Object item = createItemMethod.invoke(registry, definition, Math.max(1, amount));
            if (!(item instanceof ItemStack stack)) {
                return null;
            }
            stack.setAmount(Math.max(1, amount));
            return stack;
        } catch (Exception exception) {
            logger.warning("HexCustomMobs: Błąd przy tworzeniu itemu z HexCustomItems '" + customItemId + "': " + exception.getMessage());
            return null;
        }
    }

    private Object resolveRegistry() {
        PluginManager pluginManager = Bukkit.getPluginManager();
        Plugin plugin = pluginManager.getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled()) {
            invalidateCache();
            return null;
        }
        if (plugin == cachedPlugin && cachedRegistryService != null && findByIdMethod != null && createItemMethod != null) {
            return cachedRegistryService;
        }

        try {
            Class<?> pluginClass = plugin.getClass();
            Field registryField = pluginClass.getDeclaredField("registryService");
            registryField.setAccessible(true);
            Object registry = registryField.get(plugin);
            if (registry == null) {
                invalidateCache();
                return null;
            }

            Class<?> registryClass = registry.getClass();
            Method findById = registryClass.getMethod("findById", String.class);
            Method createItem = registryClass.getMethod("createItem", findById.getReturnType(), int.class);

            this.cachedPlugin = plugin;
            this.cachedRegistryService = registry;
            this.findByIdMethod = findById;
            this.createItemMethod = createItem;
            return registry;
        } catch (Exception exception) {
            logger.warning("HexCustomMobs: Nie udało się podpiąć pod HexCustomItems: " + exception.getMessage());
            invalidateCache();
            return null;
        }
    }

    private void invalidateCache() {
        this.cachedPlugin = null;
        this.cachedRegistryService = null;
        this.findByIdMethod = null;
        this.createItemMethod = null;
    }
}
