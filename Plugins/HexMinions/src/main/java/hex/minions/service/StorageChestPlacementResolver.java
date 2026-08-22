package hex.minions.service;

import hex.minions.config.StorageChestDefinition;
import hex.minions.config.StorageChestRegistry;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/** Strict resolver shared by placement paths and tests. It never falls back to another storage definition. */
public final class StorageChestPlacementResolver {
    private StorageChestPlacementResolver() { }

    public static Optional<StorageChestDefinition> resolve(ItemStack item,
                                                           MinionItemFactory itemFactory,
                                                           StorageChestRegistry registry) {
        if (itemFactory == null || registry == null) return Optional.empty();
        return itemFactory.readStorageChestItem(item).flatMap(data -> resolveId(data.id(), registry));
    }

    public static Optional<StorageChestDefinition> resolveId(String id, StorageChestRegistry registry) {
        if (registry == null || id == null || id.isBlank()) return Optional.empty();
        return registry.find(id);
    }
}
