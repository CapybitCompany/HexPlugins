package hex.parkour.service;

import hex.parkour.config.ItemConfig;
import hex.parkour.config.ParkourConfig;
import hex.parkour.model.ParkourPlayerSession;
import hex.parkour.model.ParkourPlayerState;
import hex.parkour.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class ParkourItemService {
    private final NamespacedKey itemKey;

    public ParkourItemService(Plugin plugin) {
        this.itemKey = new NamespacedKey(plugin, "parkour_item");
    }

    public void giveLobbyItems(PlayerInventory inventory, ParkourConfig config) {
        clearParkourInventory(inventory);
        inventory.setItem(config.lobbyVisibilityItem().slot(), item(config.lobbyVisibilityItem(), ParkourItemType.VISIBILITY));
        inventory.setItem(config.lobbyLeaveItem().slot(), item(config.lobbyLeaveItem(), ParkourItemType.LEAVE_PARKOUR));
        inventory.setHeldItemSlot(Math.max(0, Math.min(8, config.lobbyVisibilityItem().slot())));
    }

    public void giveArenaItems(PlayerInventory inventory, ParkourConfig config) {
        clearParkourInventory(inventory);
        inventory.setItem(config.arenaResetItem().slot(), item(config.arenaResetItem(), ParkourItemType.RESET_CHECKPOINT));
        inventory.setItem(config.arenaVisibilityItem().slot(), item(config.arenaVisibilityItem(), ParkourItemType.VISIBILITY));
        inventory.setItem(config.arenaLobbyItem().slot(), item(config.arenaLobbyItem(), ParkourItemType.RETURN_LOBBY));
        inventory.setHeldItemSlot(Math.max(0, Math.min(8, config.arenaResetItem().slot())));
    }

    public void clearParkourInventory(PlayerInventory inventory) {
        inventory.clear();
        inventory.setArmorContents(null);
        inventory.setExtraContents(null);
    }

    public ItemStack item(ItemConfig config, ParkourItemType type) {
        ItemStack item = new ItemStack(config.material() == null ? Material.STONE : config.material());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(config.name()));
            meta.setLore(Text.color(config.lore()));
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, type.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    public ParkourItemType type(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String raw = meta.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return ParkourItemType.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public ParkourItemType type(ItemStack item, int heldSlot, ParkourPlayerSession session, ParkourConfig config) {
        ParkourItemType tagged = type(item);
        if (tagged != null || item == null || item.getType() == Material.AIR || session == null || config == null) return tagged;
        if (session.state() == ParkourPlayerState.PARKOUR_LOBBY) {
            if (matches(item, heldSlot, config.lobbyVisibilityItem())) return ParkourItemType.VISIBILITY;
            if (matches(item, heldSlot, config.lobbyLeaveItem())) return ParkourItemType.LEAVE_PARKOUR;
            if (matchesMaterial(item, config.lobbyVisibilityItem())) return ParkourItemType.VISIBILITY;
            if (matchesMaterial(item, config.lobbyLeaveItem())) return ParkourItemType.LEAVE_PARKOUR;
            return null;
        }
        if (matches(item, heldSlot, config.arenaResetItem())) return ParkourItemType.RESET_CHECKPOINT;
        if (matches(item, heldSlot, config.arenaVisibilityItem())) return ParkourItemType.VISIBILITY;
        if (matches(item, heldSlot, config.arenaLobbyItem())) return ParkourItemType.RETURN_LOBBY;
        if (matchesMaterial(item, config.arenaResetItem())) return ParkourItemType.RESET_CHECKPOINT;
        if (matchesMaterial(item, config.arenaVisibilityItem())) return ParkourItemType.VISIBILITY;
        if (matchesMaterial(item, config.arenaLobbyItem())) return ParkourItemType.RETURN_LOBBY;
        return null;
    }

    public boolean couldBeParkourControl(ItemStack item, ParkourConfig config) {
        if (type(item) != null) return true;
        if (item == null || item.getType() == Material.AIR || config == null) return false;
        return matchesMaterial(item, config.lobbyVisibilityItem())
                || matchesMaterial(item, config.lobbyLeaveItem())
                || matchesMaterial(item, config.arenaResetItem())
                || matchesMaterial(item, config.arenaVisibilityItem())
                || matchesMaterial(item, config.arenaLobbyItem());
    }

    public boolean isParkourItem(ItemStack item) {
        return type(item) != null;
    }

    private boolean matches(ItemStack item, int slot, ItemConfig config) {
        return config != null && slot == config.slot() && matchesMaterial(item, config);
    }

    private boolean matchesMaterial(ItemStack item, ItemConfig config) {
        return item != null && config != null && item.getType() == config.material();
    }
}
