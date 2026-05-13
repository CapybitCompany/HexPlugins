package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.util.LegacyTextUtil;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class CustomItemRegistryService {

    private final NamespacedKey itemIdKey;
    private final AtomicReference<Map<String, CustomItemDefinition>> itemsRef = new AtomicReference<>(Map.of());

    public CustomItemRegistryService(JavaPlugin plugin, HexCustomItemsConfig initialConfig) {
        this.itemIdKey = new NamespacedKey(plugin, "hexcustomitem_id");
        updateConfig(initialConfig);
    }

    public void updateConfig(HexCustomItemsConfig config) {
        this.itemsRef.set(config.items());
    }

    public Map<String, CustomItemDefinition> allItems() {
        return itemsRef.get();
    }

    public CustomItemDefinition findById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return itemsRef.get().get(id.toLowerCase(Locale.ROOT));
    }

    public ItemStack createItem(CustomItemDefinition definition, int amount) {
        Objects.requireNonNull(definition, "definition");

        ItemStack item = new ItemStack(definition.material(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(LegacyTextUtil.colorize(definition.name()));
            meta.setLore(LegacyTextUtil.colorize(definition.lore()));
            meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, definition.id());
            item.setItemMeta(meta);
        }
        return item;
    }

    public String resolveItemId(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return null;
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            String direct = meta.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
            if (direct != null && !direct.isBlank()) {
                return direct.toLowerCase(Locale.ROOT);
            }
        }

        return matchBySignature(itemStack);
    }

    public boolean isManagedItem(ItemStack itemStack) {
        return resolveItemId(itemStack) != null;
    }

    private String matchBySignature(ItemStack itemStack) {
        ItemMeta meta = itemStack.getItemMeta();
        String displayName = meta == null ? null : meta.getDisplayName();
        for (CustomItemDefinition definition : itemsRef.get().values()) {
            if (itemStack.getType() != definition.material()) {
                continue;
            }
            if (displayName != null && displayName.equals(LegacyTextUtil.colorize(definition.name()))) {
                return definition.id();
            }
        }
        return null;
    }
}
