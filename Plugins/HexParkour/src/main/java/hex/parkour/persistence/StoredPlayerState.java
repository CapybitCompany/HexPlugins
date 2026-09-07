package hex.parkour.persistence;

import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.List;
import java.util.UUID;

public record StoredPlayerState(
        UUID playerId,
        String playerName,
        UUID instanceId,
        long createdAtMillis,
        boolean restorePending,
        ItemStack[] inventoryContents,
        ItemStack[] armorContents,
        ItemStack[] extraContents,
        ItemStack cursorItem,
        int heldSlot,
        GameMode gameMode,
        boolean allowFlight,
        boolean flying,
        float exp,
        int level,
        int totalExperience,
        double health,
        double absorption,
        int foodLevel,
        float saturation,
        float exhaustion,
        List<PotionEffect> potionEffects,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
}
