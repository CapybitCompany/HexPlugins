package hexchests;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class CustomItemsBridge {

    private final JavaPlugin plugin;
    private final NamespacedKey customItemIdKey;

    public CustomItemsBridge(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.customItemIdKey = new NamespacedKey("hexcustomitems", "hexcustomitem_id");
    }

    public Optional<String> itemId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        String id = meta.getPersistentDataContainer().get(customItemIdKey, PersistentDataType.STRING);
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(normalize(id));
    }

    public Optional<ItemStack> createItem(String id, int amount, OfflinePlayer player) {
        Object registry = registryService();
        if (registry == null) {
            return Optional.empty();
        }
        Object definition = findDefinition(registry, id);
        if (definition == null) {
            return Optional.empty();
        }
        Method create = method(registry.getClass(), "createItem", 3);
        if (create == null) {
            create = method(registry.getClass(), "createItem", 2);
        }
        if (create == null) {
            return Optional.empty();
        }

        try {
            Object created = create.getParameterCount() == 3
                    ? create.invoke(registry, definition, Math.max(1, amount), player)
                    : create.invoke(registry, definition, Math.max(1, amount));
            return created instanceof ItemStack stack ? Optional.of(stack) : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().fine("HexCustomItems preview bridge failed for " + id + ": " + ex.getMessage());
            return Optional.empty();
        }
    }

    public boolean give(Player player, String id, int amount) {
        return giveDirect(player, id, amount) || dispatchGive(player, id, amount);
    }

    private boolean giveDirect(Player player, String id, int amount) {
        Object registry = registryService();
        Object giveService = service("giveService");
        if (registry == null || giveService == null) {
            return false;
        }
        Object definition = findDefinition(registry, id);
        if (definition == null) {
            return false;
        }
        Method giveTo = method(giveService.getClass(), "giveTo", 3);
        if (giveTo == null) {
            return false;
        }
        try {
            giveTo.invoke(giveService, player, definition, Math.max(1, amount));
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().fine("HexCustomItems direct give bridge failed for " + id + ": " + ex.getMessage());
            return false;
        }
    }

    private boolean dispatchGive(Player player, String id, int amount) {
        String command = "hexcustomitem " + normalize(id) + " " + player.getName() + " " + Math.max(1, amount);
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private Object registryService() {
        return service("registryService");
    }

    private Object service(String fieldName) {
        Plugin hexCustomItems = Bukkit.getPluginManager().getPlugin("HexCustomItems");
        if (hexCustomItems == null || !hexCustomItems.isEnabled()) {
            return null;
        }
        return field(hexCustomItems, fieldName);
    }

    private Object findDefinition(Object registry, String id) {
        Method findById = method(registry.getClass(), "findById", 1);
        if (findById == null) {
            return null;
        }
        try {
            return findById.invoke(registry, normalize(id));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().fine("HexCustomItems lookup bridge failed for " + id + ": " + ex.getMessage());
            return null;
        }
    }

    private Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException | RuntimeException ex) {
                plugin.getLogger().fine("HexCustomItems field bridge failed for " + name + ": " + ex.getMessage());
                return null;
            }
        }
        return null;
    }

    private Method method(Class<?> type, String name, int parameterCount) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        return null;
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim();
    }
}
