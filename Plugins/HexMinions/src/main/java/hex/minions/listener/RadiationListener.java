package hex.minions.listener;

import hex.minions.service.MinionService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;

public final class RadiationListener implements Listener {
    private final Plugin plugin;
    private final MinionService service;

    public RadiationListener(Plugin plugin, MinionService service) {
        this.plugin = plugin;
        this.service = service;
        Bukkit.getScheduler().runTaskTimer(plugin, this::damagePlayers, 20L, 20L);
    }

    private void damagePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            int enriched = countEnriched(player.getInventory());
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof Chest) enriched += countEnriched(top);
            if (enriched <= 0) continue;
            double reduction = radiationReduction(player);
            double damage = (2.0D * enriched) * Math.max(0.0D, 1.0D - reduction);
            if (damage > 0.0D) player.damage(damage);
            player.sendActionBar(net.kyori.adventure.text.Component.text("§a☢ §cPromieniowanie: §f" + enriched + "x wzbogacony uran §7(ochrona " + Math.round(reduction * 100.0D) + "%)"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpenChest(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof Chest)) return;
        int enriched = countEnriched(event.getInventory());
        if (enriched > 0) {
            double reduction = radiationReduction(player);
            double damage = (2.0D * enriched) * Math.max(0.0D, 1.0D - reduction);
            if (damage > 0.0D) player.damage(damage);
            player.sendMessage("§cW skrzyni znajduje się wzbogacony uran. Ochrona kombinezonu: " + Math.round(reduction * 100.0D) + "%.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClickChest(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Chest)) return;
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        if ((isEnriched(cursor) || isEnriched(current)) && event.getClickedInventory() != null && event.getClickedInventory().equals(top)) {
            event.setCancelled(true);
            player.sendMessage("§cWzbogaconego uranu nie można wkładać do zwykłej skrzyni.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDragChest(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Chest)) return;
        boolean touchesChest = event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize());
        if (!touchesChest) return;
        for (ItemStack item : event.getNewItems().values()) {
            if (isEnriched(item)) {
                event.setCancelled(true);
                player.sendMessage("§cWzbogaconego uranu nie można wkładać do zwykłej skrzyni.");
                return;
            }
        }
    }

    private double radiationReduction(Player player) {
        if (player == null) return 0.0D;
        double reduction = 0.0D;
        reduction += armorPieceReduction(player.getInventory().getHelmet(), "hazmat_helmet", 0.20D);
        reduction += armorPieceReduction(player.getInventory().getChestplate(), "hazmat_chestplate", 0.40D);
        reduction += armorPieceReduction(player.getInventory().getLeggings(), "hazmat_leggings", 0.30D);
        reduction += armorPieceReduction(player.getInventory().getBoots(), "hazmat_boots", 0.10D);
        return Math.max(0.0D, Math.min(1.0D, reduction));
    }

    private double armorPieceReduction(ItemStack item, String specialId, double value) {
        if (item == null || item.getType() == Material.AIR) return 0.0D;
        return service.specialItems().readSpecialItemId(item).map(id -> id.equalsIgnoreCase(specialId) ? value : 0.0D).orElse(0.0D);
    }

    private int countEnriched(Inventory inventory) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) if (isEnriched(item)) count += item.getAmount();
        return count;
    }

    private boolean isEnriched(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        return service.specialItems().readSpecialItemId(item).map("enriched_uranium"::equalsIgnoreCase).orElse(false);
    }
}
