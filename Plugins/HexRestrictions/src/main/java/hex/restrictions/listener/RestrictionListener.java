package hex.restrictions.listener;

import hex.restrictions.HexRestrictionsPlugin;
import hex.restrictions.service.ItemSanitizeResult;
import hex.restrictions.service.RestrictionService;
import hex.restrictions.service.WorldScanService;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import io.papermc.paper.event.player.PlayerPurchaseEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.entity.VillagerReplenishTradeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

public final class RestrictionListener implements Listener {
    private final HexRestrictionsPlugin plugin;
    private final RestrictionService restrictions;
    private final WorldScanService worldScanner;

    public RestrictionListener(HexRestrictionsPlugin plugin, RestrictionService restrictions, WorldScanService worldScanner) {
        this.plugin = plugin;
        this.restrictions = restrictions;
        this.worldScanner = worldScanner;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (!restrictions.isEnabled()) return;
        int before = event.getEnchantsToAdd().size();
        event.getEnchantsToAdd().entrySet().removeIf(entry -> restrictions.isForbiddenEnchantment(entry.getKey()));
        if (before == event.getEnchantsToAdd().size()) return;

        if (event.getEnchantsToAdd().isEmpty()) event.setCancelled(true);
        plugin.notifyBlockedEnchantment(event.getEnchanter());
        plugin.logBlocked("Blocked forbidden enchantment at enchanting table for " + event.getEnchanter().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!restrictions.isEnabled()) return;
        ItemStack result = event.getResult();
        if (result == null) return;
        if (restrictions.isForbiddenMaterial(result.getType())) {
            event.setResult(null);
            return;
        }
        if (restrictions.hasForbiddenEnchantment(result)) {
            // Do not let an anvil charge levels for an operation whose forbidden enchant would be stripped.
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        sanitizePreparedResult(event.getResult(), event::setResult);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (!restrictions.isEnabled() || result == null) return;
        ItemSanitizeResult sanitized = restrictions.sanitizeItem(result);
        if (sanitized.changed()) event.getInventory().setResult(sanitized.item());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLootGenerate(LootGenerateEvent event) {
        sanitizeList(event.getLoot());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        sanitizeList(event.getDrops());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent event) {
        Iterator<Item> iterator = event.getItems().iterator();
        while (iterator.hasNext()) {
            Item entity = iterator.next();
            ItemSanitizeResult result = restrictions.sanitizeItem(entity.getItemStack());
            if (!result.changed()) continue;
            if (result.item() == null) {
                iterator.remove();
            } else {
                entity.setItemStack(result.item());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        ItemSanitizeResult result = restrictions.sanitizeItem(event.getItem());
        if (!result.changed()) return;
        if (result.item() == null) event.setCancelled(true);
        else event.setItem(result.item());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!restrictions.isForbiddenMaterial(event.getItemInHand().getType())) return;
        event.setCancelled(true);
        restrictions.sanitizePlayer(event.getPlayer());
        plugin.notifyBlockedItem(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemSanitizeResult result = restrictions.sanitizeItem(event.getEntity().getItemStack());
        if (!result.changed()) return;
        if (result.item() == null) event.setCancelled(true);
        else event.getEntity().setItemStack(result.item());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();
        ItemSanitizeResult result = restrictions.sanitizeItem(item.getItemStack());
        if (!result.changed()) return;
        if (result.item() == null) {
            event.setCancelled(true);
            item.remove();
        } else {
            item.setItemStack(result.item());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        ItemSanitizeResult result = restrictions.sanitizeItem(event.getItem().getItemStack());
        if (!result.changed()) return;
        if (result.item() == null) {
            event.setCancelled(true);
            event.getItem().remove();
        } else {
            event.getItem().setItemStack(result.item());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (!restrictions.hasForbiddenContent(event.getItem())) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            restrictions.sanitizeInventory(event.getSource());
            restrictions.sanitizeInventory(event.getDestination());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVillagerAcquire(VillagerAcquireTradeEvent event) {
        if (!restrictions.hasForbiddenContent(event.getRecipe().getResult())) return;
        event.setCancelled(true);
        plugin.logBlocked("Blocked forbidden villager trade acquisition for entity " + event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVillagerReplenish(VillagerReplenishTradeEvent event) {
        if (restrictions.hasForbiddenContent(event.getRecipe().getResult())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPurchase(PlayerPurchaseEvent event) {
        if (!restrictions.hasForbiddenContent(event.getTrade().getResult())) return;
        event.setCancelled(true);
        if (restrictions.isForbiddenMaterial(event.getTrade().getResult().getType())) plugin.notifyBlockedItem(event.getPlayer());
        else plugin.notifyBlockedEnchantment(event.getPlayer());
        plugin.logBlocked("Blocked forbidden merchant trade for " + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMend(PlayerItemMendEvent event) {
        if (!restrictions.settings().blockRuntimeEffects()) return;
        if (!restrictions.hasForbiddenEnchantment(event.getItem())) return;
        event.setCancelled(true);
        ItemSanitizeResult result = restrictions.sanitizeItem(event.getItem());
        if (result.changed() && result.item() != null) {
            event.getItem().setItemMeta(result.item().getItemMeta());
        }
        plugin.logBlocked("Blocked runtime effect of forbidden enchantment for " + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || !restrictions.isForbiddenMaterial(item.getType())) return;
        event.setCancelled(true);
        restrictions.sanitizePlayer(event.getPlayer());
        plugin.notifyBlockedItem(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!restrictions.isForbiddenMaterial(event.getItem().getType())) return;
        event.setCancelled(true);
        restrictions.sanitizePlayer(event.getPlayer());
        plugin.notifyBlockedItem(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!restrictions.settings().scanPlayersOnJoin()) return;
        Bukkit.getScheduler().runTask(plugin, () -> plugin.scanAndNotify(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!restrictions.settings().scanPlayersOnRespawn()) return;
        Bukkit.getScheduler().runTask(plugin, () -> plugin.scanAndNotify(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerSlotChange(PlayerInventorySlotChangeEvent event) {
        if (!restrictions.isEnabled() || !restrictions.hasForbiddenContent(event.getNewItemStack())) return;
        int slot = event.getSlot();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack current = player.getInventory().getItem(slot);
            ItemSanitizeResult result = restrictions.sanitizeItem(current);
            if (result.changed()) player.getInventory().setItem(slot, result.item());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!restrictions.settings().scanContainersOnOpen()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            restrictions.sanitizeInventory(event.getInventory());
            if (event.getPlayer() instanceof Player player) restrictions.sanitizePlayer(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            restrictions.sanitizePlayer(player);
            Inventory top = player.getOpenInventory().getTopInventory();
            restrictions.sanitizeInventory(top);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            restrictions.sanitizePlayer(player);
            restrictions.sanitizeInventory(player.getOpenInventory().getTopInventory());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (restrictions.settings().scanContainersOnChunkLoad()) worldScanner.queue(event.getChunk());
    }

    private void sanitizePreparedResult(ItemStack current, java.util.function.Consumer<ItemStack> setter) {
        if (!restrictions.isEnabled() || current == null) return;
        ItemSanitizeResult result = restrictions.sanitizeItem(current);
        if (result.changed()) setter.accept(result.item());
    }

    private void sanitizeList(List<ItemStack> list) {
        if (!restrictions.isEnabled()) return;
        ListIterator<ItemStack> iterator = list.listIterator();
        while (iterator.hasNext()) {
            ItemStack current = iterator.next();
            ItemSanitizeResult result = restrictions.sanitizeItem(current);
            if (!result.changed()) continue;
            if (result.item() == null) iterator.remove();
            else iterator.set(result.item());
        }
    }
}
