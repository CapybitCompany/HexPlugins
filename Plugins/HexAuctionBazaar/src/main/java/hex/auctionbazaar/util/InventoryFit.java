package hex.auctionbazaar.util;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Helper for safe inventory mutation: snapshot the player's storage slots
 * before an addItem(), check whether everything fit, and on overflow restore
 * the snapshot so no items end up in the inventory.
 *
 * Reason we cannot use a pure "dry run":
 * Bukkit's addItem honors max stack sizes, partial stacks, and similarity
 * rules that we don't want to re-implement. snapshot/restore is the only
 * truly reliable way to keep an all-or-nothing semantic without item dupes.
 */
public final class InventoryFit {

    private InventoryFit() {
    }

    /**
     * Attempts to add {@code stack} to the player's storage section.
     * Returns true when the FULL stack was placed; in that case the
     * inventory is mutated. Returns false when not everything fit; in
     * that case the inventory is restored to its pre-call state and
     * no items remain placed.
     */
    public static boolean tryAddFullOrRevert(Player player, ItemStack stack) {
        if (player == null || stack == null || stack.getAmount() <= 0) {
            return false;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack[] snapshot = deepCopy(inv.getStorageContents());
        var leftover = inv.addItem(stack.clone());
        if (leftover.isEmpty()) {
            return true;
        }
        // Restore. setStorageContents copies the array, so this leaves no aliases.
        inv.setStorageContents(snapshot);
        return false;
    }

    private static ItemStack[] deepCopy(ItemStack[] contents) {
        ItemStack[] out = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            out[i] = contents[i] == null ? null : contents[i].clone();
        }
        return out;
    }
}
