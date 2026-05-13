package mysterybox.service;

import mysterybox.config.MysteryBoxConfig;
import mysterybox.util.LegacyTextUtil;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class ItemFactoryService {

    private static final byte FLAG_TRUE = (byte) 1;

    private final Supplier<MysteryBoxConfig> configSupplier;
    private final NamespacedKey mysteryBoxKey;
    private final NamespacedKey vipVoucherKey;

    public ItemFactoryService(JavaPlugin plugin, Supplier<MysteryBoxConfig> configSupplier) {
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.mysteryBoxKey = new NamespacedKey(plugin, "mystery_box_item");
        this.vipVoucherKey = new NamespacedKey(plugin, "vip_voucher_item");
    }

    public ItemStack createMysteryBoxItem(int amount) {
        MysteryBoxConfig.BoxSettings box = configSupplier.get().box();
        ItemStack itemStack = new ItemStack(box.material(), Math.max(1, amount));
        applyMeta(itemStack, box.name(), box.lore());
        mark(itemStack, mysteryBoxKey);
        return itemStack;
    }

    public ItemStack createVipVoucherItem(int amount) {
        MysteryBoxConfig.VoucherSettings voucher = configSupplier.get().voucher();
        ItemStack itemStack = new ItemStack(voucher.material(), Math.max(1, amount));
        applyMeta(itemStack, voucher.name(), voucher.lore());
        mark(itemStack, vipVoucherKey);
        return itemStack;
    }

    public ItemStack createCustomItem(MysteryBoxConfig.RewardGrantItemSettings settings) {
        ItemStack itemStack = new ItemStack(settings.material(), settings.amount());
        applyMeta(itemStack, settings.name(), settings.lore());
        return itemStack;
    }

    public boolean isMysteryBoxItem(ItemStack itemStack) {
        return hasMark(itemStack, mysteryBoxKey);
    }

    public boolean isVipVoucherItem(ItemStack itemStack) {
        return hasMark(itemStack, vipVoucherKey);
    }

    public boolean consumeOneFromHand(Player player, EquipmentSlot hand, java.util.function.Predicate<ItemStack> matcher) {
        EquipmentSlot actualHand = hand == null ? EquipmentSlot.HAND : hand;
        ItemStack current = player.getInventory().getItem(actualHand);
        if (current == null || current.getType().isAir()) {
            return false;
        }
        if (!matcher.test(current)) {
            return false;
        }

        int amount = current.getAmount();
        if (amount <= 1) {
            player.getInventory().setItem(actualHand, null);
            return true;
        }

        current.setAmount(amount - 1);
        player.getInventory().setItem(actualHand, current);
        return true;
    }

    private void applyMeta(ItemStack itemStack, String name, List<String> lore) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.setDisplayName(LegacyTextUtil.colorize(name));
        meta.setLore(LegacyTextUtil.colorize(lore));
        itemStack.setItemMeta(meta);
    }

    private void mark(ItemStack itemStack, NamespacedKey key) {
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, FLAG_TRUE);
        itemStack.setItemMeta(meta);
    }

    private boolean hasMark(ItemStack itemStack, NamespacedKey key) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return false;
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte value = meta.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return value != null && value == FLAG_TRUE;
    }
}
