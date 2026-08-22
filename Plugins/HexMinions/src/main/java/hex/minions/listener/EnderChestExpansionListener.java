package hex.minions.listener;

import hex.minions.service.MinionService;
import hex.towns.api.TownsApi;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

/**
 * Bezpieczna dodatkowa linia Ender Chest za Netherite Minion Tier VII.
 * Nie zmieniamy rozmiaru vanilla ender chest. Zamiast tego otwieramy własne 36-slotowe GUI:
 *  - sloty 0-26 są synchronizowane z prawdziwym Ender Chest gracza,
 *  - sloty 27-35 są serializowane w PDC gracza.
 * Dzięki temu przy wyłączeniu funkcji lub błędzie vanilla 27 slotów zostaje nietknięte, a dodatkowa linia nie nadpisuje itemów.
 */
public final class EnderChestExpansionListener implements Listener {
    private final Plugin plugin;
    private final TownsApi towns;
    private final MinionService minions;
    private final NamespacedKey extraKey;

    public EnderChestExpansionListener(Plugin plugin, TownsApi towns, MinionService minions) {
        this.plugin = plugin;
        this.towns = towns;
        this.minions = minions;
        this.extraKey = new NamespacedKey(plugin, "ender_chest_extra_row");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpenEnderChest(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.ENDER_CHEST) return;
        Player player = event.getPlayer();
        UUID townId = towns.townIdOf(player.getUniqueId()).orElse(null);
        if (townId == null || !minions.townHasMinionLevel(townId, "netherite", 7)) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> openExpanded(player));
    }

    private void openExpanded(Player player) {
        Inventory inv = Bukkit.createInventory(new ExpandedEnderChestHolder(player.getUniqueId()), 36, "§5Ender Chest+ §8(Netherite VII)");
        Inventory vanilla = player.getEnderChest();
        for (int i = 0; i < Math.min(27, vanilla.getSize()); i++) inv.setItem(i, clone(vanilla.getItem(i)));
        ItemStack[] extra = loadExtra(player);
        for (int i = 0; i < 9; i++) inv.setItem(27 + i, clone(extra[i]));
        player.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof ExpandedEnderChestHolder holder)) return;
        if (!holder.playerId().equals(player.getUniqueId())) return;
        Inventory inv = event.getInventory();
        Inventory vanilla = player.getEnderChest();
        for (int i = 0; i < Math.min(27, vanilla.getSize()); i++) vanilla.setItem(i, clone(inv.getItem(i)));
        ItemStack[] extra = new ItemStack[9];
        for (int i = 0; i < 9; i++) extra[i] = clone(inv.getItem(27 + i));
        saveExtra(player, extra);
    }

    private ItemStack[] loadExtra(Player player) {
        ItemStack[] items = new ItemStack[9];
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        byte[] raw = pdc.get(extraKey, PersistentDataType.BYTE_ARRAY);
        if (raw == null || raw.length == 0) return items;
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(raw))) {
            Object obj = in.readObject();
            if (obj instanceof ItemStack[] stored) {
                for (int i = 0; i < Math.min(9, stored.length); i++) items[i] = clone(stored[i]);
            }
        } catch (Throwable ignored) {
            // W razie uszkodzenia danych nie otwieramy ryzykownego restore; vanilla ender chest pozostaje bezpieczny.
        }
        return items;
    }

    private void saveExtra(Player player, ItemStack[] items) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
                out.writeObject(items);
            }
            player.getPersistentDataContainer().set(extraKey, PersistentDataType.BYTE_ARRAY, bytes.toByteArray());
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Nie udało się zapisać dodatkowej linii Ender Chest gracza " + player.getName() + ": " + throwable.getMessage());
        }
    }

    private static ItemStack clone(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    private record ExpandedEnderChestHolder(UUID playerId) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}
