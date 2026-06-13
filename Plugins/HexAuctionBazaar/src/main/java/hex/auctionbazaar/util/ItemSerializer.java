package hex.auctionbazaar.util;

import org.bukkit.inventory.ItemStack;

/**
 * Full Paper ItemStack serialization including NBT.
 * Version-dependent: items written under MC 1.21.11 should remain readable
 * on 1.21.x patch releases; a major upgrade can break compatibility.
 */
public final class ItemSerializer {

    private ItemSerializer() {
    }

    public static byte[] serialize(ItemStack stack) {
        if (stack == null) {
            throw new IllegalArgumentException("stack must not be null");
        }
        return stack.serializeAsBytes();
    }

    public static ItemStack deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return ItemStack.deserializeBytes(bytes);
    }
}
