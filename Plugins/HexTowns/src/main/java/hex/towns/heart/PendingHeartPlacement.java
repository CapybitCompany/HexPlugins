package hex.towns.heart;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

record PendingHeartPlacement(UUID playerId, Location clickedLocation, String name, UUID attachTownId, ItemStack sourceItem, long expiresAt) {
    boolean expired() {
        return System.currentTimeMillis() > expiresAt;
    }

    PendingHeartPlacement withName(String newName) {
        return new PendingHeartPlacement(playerId, clickedLocation, newName, attachTownId, sourceItem, expiresAt);
    }
}
