package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.util.TextUtil;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class CustomItemRegistryService {

    private final NamespacedKey itemIdKey;
    private final NamespacedKey chargesKey;
    private final AtomicReference<Map<String, CustomItemDefinition>> itemsRef = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, String>> keyByIdRef = new AtomicReference<>(Map.of());

    public CustomItemRegistryService(JavaPlugin plugin, HexCustomItemsConfig initialConfig) {
        this.itemIdKey = new NamespacedKey(plugin, "hexcustomitem_id");
        this.chargesKey = new NamespacedKey(plugin, "hexcustomitem_charges");
        updateConfig(initialConfig);
    }

    public void updateConfig(HexCustomItemsConfig config) {
        this.itemsRef.set(config.items());
        this.keyByIdRef.set(config.itemIds());
    }

    public Map<String, CustomItemDefinition> allItems() {
        return itemsRef.get();
    }

    public CustomItemDefinition findById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        CustomItemDefinition byKey = itemsRef.get().get(normalized);
        if (byKey != null) {
            return byKey;
        }
        String key = keyByIdRef.get().get(normalized);
        return key == null ? null : itemsRef.get().get(key);
    }

    public CustomItemDefinition findByStack(ItemStack itemStack) {
        String id = resolveItemId(itemStack);
        return id == null ? null : findById(id);
    }

    public ItemStack createItem(CustomItemDefinition definition, int amount) {
        return createItem(definition, amount, null);
    }

    /**
     * Erzeugt genau ein ItemStack (bei Ladungs-Items immer Stackgröße 1).
     * {@code papiContext} ist der Spieler, für den Name/Lore via PlaceholderAPI
     * aufgelöst werden (z.B. Zielspieler beim Geben) - kann {@code null} sein.
     */
    public ItemStack createItem(CustomItemDefinition definition, int amount, OfflinePlayer papiContext) {
        Objects.requireNonNull(definition, "definition");

        int stackSize = definition.usesCharges() ? 1 : Math.max(1, amount);
        ItemStack item = new ItemStack(definition.material(), stackSize);
        applyMeta(item, definition, definition.charges(), papiContext);
        return item;
    }

    /** Nur PDC-basierte Erkennung - kein Signatur-Scan pro Klick mehr. */
    public String resolveItemId(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return null;
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return null;
        }
        String direct = meta.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        if (direct == null || direct.isBlank()) {
            return null;
        }
        return direct.toLowerCase(Locale.ROOT);
    }

    public boolean isManagedItem(ItemStack itemStack) {
        return resolveItemId(itemStack) != null;
    }

    /** Aktuelle Ladungen eines Items; -1 wenn das Item kein Ladungs-System nutzt. */
    public int readCharges(ItemStack itemStack) {
        if (itemStack == null) {
            return -1;
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return -1;
        }
        Integer value = meta.getPersistentDataContainer().get(chargesKey, PersistentDataType.INTEGER);
        return value == null ? -1 : value;
    }

    /** Setzt die verbleibenden Ladungen und rendert die Lore neu. */
    public void writeCharges(ItemStack itemStack, CustomItemDefinition definition, int remaining, OfflinePlayer papiContext) {
        applyMeta(itemStack, definition, remaining, papiContext);
    }

    private void applyMeta(ItemStack item, CustomItemDefinition definition, int remainingCharges, OfflinePlayer papiContext) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        Map<String, String> placeholders = new HashMap<>();
        if (definition.usesCharges()) {
            placeholders.put("charges", String.valueOf(Math.max(0, remainingCharges)));
            placeholders.put("max_charges", String.valueOf(definition.charges()));
        }

        meta.displayName(TextUtil.itemName(definition.name(), placeholders, papiContext));
        meta.lore(TextUtil.itemLore(definition.lore(), placeholders, papiContext));
        if (definition.modelData() > 0) {
            meta.setCustomModelData(definition.modelData());
        }
        if (definition.glint()) {
            applyGlint(meta);
        }
        meta.addItemFlags(ItemFlag.values());

        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(itemIdKey, PersistentDataType.STRING, definition.id());
        if (definition.usesCharges()) {
            data.set(chargesKey, PersistentDataType.INTEGER, Math.max(0, remainingCharges));
        }

        item.setItemMeta(meta);
    }

    private void applyGlint(ItemMeta meta) {
        try {
            Method method = meta.getClass().getMethod("setEnchantmentGlintOverride", Boolean.class);
            method.invoke(meta, Boolean.TRUE);
            return;
        } catch (ReflectiveOperationException ignored) {
            // Older test/API surface: fall back to a hidden harmless enchant where possible.
        }
        Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"));
        if (enchantment == null) {
            return;
        }
        if (meta instanceof EnchantmentStorageMeta storage) {
            storage.addStoredEnchant(enchantment, 1, true);
        } else {
            meta.addEnchant(enchantment, 1, true);
        }
    }
}
