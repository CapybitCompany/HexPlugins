package hex.events.provider;

import hex.events.api.CostCheck;
import hex.events.api.CostOperationResult;
import hex.events.api.CostProvider;
import hex.events.api.CostReceipt;
import hex.events.api.EventModuleSettings;
import hex.events.api.PlayerContext;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class VanillaItemCostProvider implements CostProvider {
    @Override public String type() { return "vanilla_item"; }
    private Material material(EventModuleSettings settings) { return Material.matchMaterial(settings.string("material", "")); }
    private int amount(EventModuleSettings settings) { return Math.max(1, settings.integer("amount", 1)); }
    @Override public CostCheck validate(PlayerContext player, EventModuleSettings settings) {
        Player online = Bukkit.getPlayer(player.playerId());
        Material material = material(settings);
        if (online == null) return CostCheck.fail("Gracz musi być online.");
        if (material == null || material.isAir()) return CostCheck.fail("Niepoprawny material kosztu.");
        int wanted = amount(settings), found = 0;
        for (ItemStack stack : online.getInventory().getContents()) if (stack != null && stack.getType() == material) found += stack.getAmount();
        return found >= wanted ? CostCheck.ok() : CostCheck.fail("Brakuje " + material + " x" + wanted);
    }
    @Override public CostOperationResult charge(PlayerContext player, EventModuleSettings settings, String costId, String idempotencyKey) {
        Player online = Bukkit.getPlayer(player.playerId());
        Material material = material(settings); int wanted = amount(settings);
        if (online == null || material == null) return CostOperationResult.failed("Player/material unavailable", false);
        if (!validate(player, settings).success()) return CostOperationResult.failed("NOT_ENOUGH_ITEMS", false);
        int remaining = wanted;
        ItemStack[] contents = online.getInventory().getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot]; if (stack == null || stack.getType() != material) continue;
            int take = Math.min(remaining, stack.getAmount());
            if (take == stack.getAmount()) online.getInventory().setItem(slot, null);
            else { ItemStack clone = stack.clone(); clone.setAmount(stack.getAmount() - take); online.getInventory().setItem(slot, clone); }
            remaining -= take;
        }
        if (remaining != 0) return CostOperationResult.failed("INVENTORY_CHANGED", false);
        return CostOperationResult.charged(new CostReceipt(type(), costId, Map.of("material", material.name(), "amount", String.valueOf(wanted))));
    }
    @Override public CostOperationResult refund(PlayerContext player, CostReceipt receipt, String idempotencyKey) {
        Player online = Bukkit.getPlayer(player.playerId());
        if (online == null) return CostOperationResult.failed("PLAYER_OFFLINE", true);
        Material material = Material.matchMaterial(receipt.data().getOrDefault("material", ""));
        if (material == null) return CostOperationResult.failed("UNKNOWN_MATERIAL", false);
        int amount;
        try { amount = Integer.parseInt(receipt.data().getOrDefault("amount", "1")); }
        catch (NumberFormatException ex) { return CostOperationResult.failed("INVALID_RECEIPT_AMOUNT", false); }
        if (amount <= 0) return CostOperationResult.failed("INVALID_RECEIPT_AMOUNT", false);
        if (!canFit(online, material, amount)) return CostOperationResult.failed("INVENTORY_FULL", true);
        int remaining = amount;
        int maxStack = Math.max(1, material.getMaxStackSize());
        while (remaining > 0) {
            int stackAmount = Math.min(maxStack, remaining);
            Map<Integer, ItemStack> left = online.getInventory().addItem(new ItemStack(material, stackAmount));
            if (!left.isEmpty()) return CostOperationResult.failed("INVENTORY_CHANGED", true);
            remaining -= stackAmount;
        }
        return CostOperationResult.refunded();
    }

    private static boolean canFit(Player player, Material material, int amount) {
        int free = 0;
        ItemStack[] storage;
        try { storage = player.getInventory().getStorageContents(); }
        catch (NoSuchMethodError ignored) { storage = player.getInventory().getContents(); }
        int max = Math.max(1, material.getMaxStackSize());
        for (ItemStack stack : storage) {
            if (stack == null || stack.getType().isAir()) free += max;
            else if (stack.getType() == material) free += Math.max(0, max - stack.getAmount());
            if (free >= amount) return true;
        }
        return free >= amount;
    }
}
