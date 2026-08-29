package hexcustomitems.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Public, implementation-independent contract for other Hex plugins.
 * Consumers must obtain this service through Bukkit ServicesManager.
 */
public interface HexCustomItemsApi {
    Optional<ItemStack> create(String itemId, int amount);
    String resolveId(ItemStack stack);
    int count(Player player, String itemId);
    boolean has(Player player, String itemId, int amount);
    TakeResult take(Player player, String itemId, int amount);
    GiveResult give(Player player, String itemId, int amount);
}
