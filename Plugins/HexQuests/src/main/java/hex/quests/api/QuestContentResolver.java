package hex.quests.api;

import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

/**
 * Optional extension point for custom-item/custom-mob plugins.
 * A future content plugin may register one implementation in Bukkit ServicesManager.
 */
public interface QuestContentResolver {
    default ItemStack createCustomItem(String customId, int amount) { return null; }

    default String customItemId(ItemStack stack) { return null; }

    default String customMobId(Entity entity) { return null; }

    default boolean isQuestEligibleMob(Entity entity) { return true; }
}
