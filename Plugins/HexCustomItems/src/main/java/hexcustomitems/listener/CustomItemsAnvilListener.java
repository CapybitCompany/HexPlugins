package hexcustomitems.listener;

import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.service.CustomItemRegistryService;
import hexcustomitems.service.MessageService;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

public final class CustomItemsAnvilListener implements Listener {

    private final CustomItemRegistryService registryService;
    private final MessageService messages;

    public CustomItemsAnvilListener(CustomItemRegistryService registryService, MessageService messages) {
        this.registryService = registryService;
        this.messages = messages;
    }

    @EventHandler
    public void onPrepare(PrepareAnvilEvent event) {
        AnvilInventory inventory = event.getInventory();
        ItemStack first = inventory.getFirstItem();
        ItemStack second = inventory.getSecondItem();
        if (first == null && second == null) {
            return;
        }

        CustomItemDefinition firstDef = registryService.findByStack(first);
        CustomItemDefinition secondDef = registryService.findByStack(second);
        if (isEfficiencyBook(secondDef) && canApplyEfficiency(first)) {
            ItemStack result = first.clone();
            ItemMeta meta = result.getItemMeta();
            if (meta != null) {
                Enchantment efficiency = efficiency();
                meta.addEnchant(efficiency, 6, true);
                result.setItemMeta(meta);
                event.setResult(result);
                inventory.setRepairCost(8);
            }
            return;
        }

        if (blockedInAnvil(firstDef) || blockedInAnvil(secondDef)) {
            event.setResult(null);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof AnvilInventory inventory) || event.getRawSlot() != 2) {
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() == Material.AIR) {
            return;
        }
        CustomItemDefinition secondDef = registryService.findByStack(inventory.getSecondItem());
        if (isEfficiencyBook(secondDef)) {
            ItemStack first = inventory.getFirstItem();
            if (canApplyEfficiency(first) && event.getWhoClicked() instanceof Player player) {
                event.setCancelled(true);
                ItemStack efficiencyResult = efficiencyResult(first);
                takeOne(inventory, 0);
                takeOne(inventory, 1);
                giveOrDrop(player, efficiencyResult);
            }
            return;
        }
        CustomItemDefinition firstDef = registryService.findByStack(inventory.getFirstItem());
        if (blockedInAnvil(firstDef) || blockedInAnvil(secondDef)) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof org.bukkit.entity.Player player) {
                messages.sendAnvilBlocked(player);
            }
        }
    }

    private boolean blockedInAnvil(CustomItemDefinition definition) {
        return definition != null && !definition.canUseInAnvil();
    }

    private boolean isEfficiencyBook(CustomItemDefinition definition) {
        return definition != null && "hex:efficiency_6_book".equals(definition.id());
    }

    private boolean canApplyEfficiency(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        return switch (stack.getType()) {
            case WOODEN_PICKAXE, STONE_PICKAXE, IRON_PICKAXE, GOLDEN_PICKAXE, DIAMOND_PICKAXE, NETHERITE_PICKAXE,
                 WOODEN_AXE, STONE_AXE, IRON_AXE, GOLDEN_AXE, DIAMOND_AXE, NETHERITE_AXE,
                 WOODEN_SHOVEL, STONE_SHOVEL, IRON_SHOVEL, GOLDEN_SHOVEL, DIAMOND_SHOVEL, NETHERITE_SHOVEL,
                 WOODEN_HOE, STONE_HOE, IRON_HOE, GOLDEN_HOE, DIAMOND_HOE, NETHERITE_HOE -> true;
            default -> false;
        };
    }

    private ItemStack efficiencyResult(ItemStack first) {
        ItemStack result = first.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            meta.addEnchant(efficiency(), 6, true);
            result.setItemMeta(meta);
        }
        return result;
    }

    private void takeOne(AnvilInventory inventory, int slot) {
        ItemStack stack = inventory.getItem(slot);
        if (stack == null || stack.getType() == Material.AIR) {
            return;
        }
        if (stack.getAmount() <= 1) {
            inventory.setItem(slot, null);
            return;
        }
        stack.setAmount(stack.getAmount() - 1);
        inventory.setItem(slot, stack);
    }

    private void giveOrDrop(Player player, ItemStack item) {
        var overflow = player.getInventory().addItem(item);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private Enchantment efficiency() {
        Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("efficiency"));
        if (enchantment == null) {
            throw new IllegalStateException("Missing efficiency enchantment");
        }
        return enchantment;
    }
}
