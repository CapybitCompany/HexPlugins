package hexnpc.shop.item;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Opcjonalny, fail-safe adapter do HexCustomItems bez compile-time dependency.
 * Jeśli plugin/ID/API nie istnieje, zwraca empty — zakup nie może wtedy pobrać waluty.
 */
public final class HexCustomItemsBridge {

    private final Logger logger;
    private volatile boolean warnedApiMismatch;

    public HexCustomItemsBridge(Logger logger) {
        this.logger = logger;
    }

    public Optional<ItemStack> create(String itemId, int amount) {
        if (itemId == null || itemId.isBlank()) return Optional.empty();
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("HexCustomItems");
            if (plugin == null || !plugin.isEnabled()) return Optional.empty();

            Object registry = registryOf(plugin);
            if (registry == null) return Optional.empty();

            Method find = registry.getClass().getMethod("findById", String.class);
            Object definition = find.invoke(registry, itemId);
            if (definition == null) return Optional.empty();

            Method create = null;
            for (Method method : registry.getClass().getMethods()) {
                if (!method.getName().equals("createItem") || method.getParameterCount() != 2) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p[1] == int.class || p[1] == Integer.class) {
                    create = method;
                    break;
                }
            }
            if (create == null) return Optional.empty();
            Object result = create.invoke(registry, definition, Math.max(1, amount));
            return result instanceof ItemStack stack ? Optional.of(stack.clone()) : Optional.empty();
        } catch (ReflectiveOperationException | LinkageError ex) {
            if (!warnedApiMismatch && logger != null) {
                warnedApiMismatch = true;
                logger.log(Level.WARNING,
                        "HexNPC: HexCustomItems integration unavailable; custom-item purchases will be rejected safely.", ex);
            }
            return Optional.empty();
        }
    }

    private Object registryOf(Plugin plugin) throws ReflectiveOperationException {
        Class<?> type = plugin.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("registryService");
                field.setAccessible(true);
                return field.get(plugin);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }
}
