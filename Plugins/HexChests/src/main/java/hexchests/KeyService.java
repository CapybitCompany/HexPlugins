package hexchests;

import hexchests.config.HexChestsConfig;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class KeyService {

    private final Supplier<HexChestsConfig> configSupplier;
    private final NamespacedKey keyIdKey;
    private final NamespacedKey markerKey;

    public KeyService(JavaPlugin plugin, Supplier<HexChestsConfig> configSupplier) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.keyIdKey = new NamespacedKey(plugin, "key_id");
        this.markerKey = new NamespacedKey(plugin, "test_key");
    }

    public Optional<HexChestsConfig.KeyDefinition> keyByCommand(String command) {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        String normalized = command.toLowerCase();
        return configSupplier.get().testKeys().keys().values().stream()
                .filter(key -> key.command().equalsIgnoreCase(normalized))
                .findFirst();
    }

    public Optional<String> keyId(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        String marker = meta.getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
        String keyId = meta.getPersistentDataContainer().get(keyIdKey, PersistentDataType.STRING);
        if (!"hexchests".equals(marker) || keyId == null || keyId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(keyId);
    }

    public ItemStack createKey(String keyId, int amount) {
        HexChestsConfig.KeyDefinition definition = configSupplier.get().testKeys().keys().get(keyId);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown HexChests key: " + keyId);
        }
        ItemStack stack = new ItemStack(definition.material(), Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.component(definition.displayName()));
            meta.lore(Text.lore(definition.lore(), Map.of()));
            if (definition.customModelData() != null) {
                meta.setCustomModelData(definition.customModelData());
            }
            meta.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, "hexchests");
            meta.getPersistentDataContainer().set(keyIdKey, PersistentDataType.STRING, definition.id());
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public boolean giveKey(Player player, String keyId, int amount) {
        ItemStack key = createKey(keyId, amount);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(key);
        return leftovers.isEmpty();
    }

    public void consumeOne(Player player) {
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack == null || stack.getType() == Material.AIR) {
            return;
        }
        if (stack.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        stack.setAmount(stack.getAmount() - 1);
        player.getInventory().setItemInMainHand(stack);
    }
}
