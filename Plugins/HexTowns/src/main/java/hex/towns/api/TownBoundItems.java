package hex.towns.api;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

/** Shared town-bound item contract used by HexTowns and dependent plugins. */
public interface TownBoundItems {
    boolean isTownBound(ItemStack item);
    Optional<UUID> originTown(ItemStack item);
    Optional<String> kind(ItemStack item);
    ItemStack bind(ItemStack item, UUID townUuid, String kind);
    boolean canUse(ItemStack item, UUID currentTownUuid);
    boolean validateOrAdopt(ItemStack item, UUID currentTownUuid, String kind);
    boolean belongsToDeadTown(ItemStack item);
    int purgeTownBound(Inventory inventory, UUID townUuid);
}
