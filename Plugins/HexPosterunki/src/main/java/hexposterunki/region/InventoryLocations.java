package hexposterunki.region;

import org.bukkit.Location;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;
import java.util.Optional;

/**
 * Resolves where an inventory physically sits.
 *
 * <p>{@code Inventory#getLocation()} is not a reliable answer: it is null for several inventory
 * types and unavailable in some server implementations. Going through the holder is precise, and
 * for a double chest it yields <b>both</b> halves - which matters because an admin only configures
 * one of the two block positions as a loot container.
 */
public final class InventoryLocations {

    private InventoryLocations() {
    }

    /** Every block position this inventory occupies; empty for inventories without a block. */
    public static List<Location> blocksOf(Inventory inventory) {
        if (inventory == null) {
            return List.of();
        }
        InventoryHolder holder = holderOf(inventory);
        if (holder instanceof DoubleChest doubleChest) {
            return List.of(doubleChest.getLeftSide(), doubleChest.getRightSide()).stream()
                    .filter(BlockInventoryHolder.class::isInstance)
                    .map(side -> ((BlockInventoryHolder) side).getBlock().getLocation())
                    .toList();
        }
        if (holder instanceof BlockInventoryHolder blockHolder) {
            return List.of(blockHolder.getBlock().getLocation());
        }
        return fallbackLocation(inventory).map(List::of).orElseGet(List::of);
    }

    /** A single representative position of this inventory, if it has one. */
    public static Optional<Location> anyBlockOf(Inventory inventory) {
        List<Location> blocks = blocksOf(inventory);
        return blocks.isEmpty() ? Optional.empty() : Optional.of(blocks.get(0));
    }

    private static InventoryHolder holderOf(Inventory inventory) {
        try {
            return inventory.getHolder();
        } catch (RuntimeException | AbstractMethodError ignored) {
            return null;
        }
    }

    private static Optional<Location> fallbackLocation(Inventory inventory) {
        try {
            return Optional.ofNullable(inventory.getLocation());
        } catch (RuntimeException | AbstractMethodError ignored) {
            // Some server implementations do not support it at all; the holder path above is
            // the primary answer anyway.
            return Optional.empty();
        }
    }
}
