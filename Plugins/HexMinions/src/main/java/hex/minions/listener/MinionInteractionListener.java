package hex.minions.listener;

import hex.core.api.HexApi;
import hex.minions.menu.MinionMenu;
import hex.minions.render.MinionRenderer;
import hex.minions.service.MinionItemFactory;
import hex.minions.service.MinionService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;

public final class MinionInteractionListener implements Listener {
    private final Plugin plugin;
    private final HexApi hex;
    private final MinionService service;
    private final MinionItemFactory itemFactory;
    private final MinionRenderer renderer;
    private final MinionMenu menu;

    public MinionInteractionListener(Plugin plugin, HexApi hex, MinionService service, MinionItemFactory itemFactory, MinionRenderer renderer, MinionMenu menu) {
        this.plugin = plugin;
        this.hex = hex;
        this.service = service;
        this.itemFactory = itemFactory;
        this.renderer = renderer;
        this.menu = menu;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUseMinionItem(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        Optional<MinionItemFactory.MinionItemData> data = itemFactory.read(item);
        if (data.isEmpty()) return;
        event.setCancelled(true);
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        Location target = clicked.getRelative(event.getBlockFace()).getLocation();
        service.place(player, target, data.get().typeId(), data.get().tier()).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            hex.ui().send(player, result.messageKey(), result.tokens());
            if (result.success()) item.setAmount(item.getAmount() - 1);
        }));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStorageChestPlace(BlockPlaceEvent event) {
        Material material = event.getBlockPlaced().getType();
        if (material != Material.CHEST && material != Material.TRAPPED_CHEST) return;
        service.adjacentMinion(event.getBlockPlaced().getLocation()).ifPresent(minion -> {
            if (!service.isValidStorageChestItem(event.getItemInHand())) {
                event.setCancelled(true);
                hex.ui().send(event.getPlayer(), "minions.storage-chest.error.special-required");
                return;
            }
            if (service.hasAdjacentStorageChest(minion, event.getBlockPlaced().getLocation())) {
                event.setCancelled(true);
                hex.ui().send(event.getPlayer(), "minions.storage-chest.error.already-has");
                return;
            }
            if (service.touchesOtherChest(event.getBlockPlaced().getLocation())) {
                event.setCancelled(true);
                hex.ui().send(event.getPlayer(), "minions.storage-chest.error.next-to-chest");
                return;
            }
            service.markPlacedStorageChest(event.getBlockPlaced(), event.getItemInHand());
            hex.ui().send(event.getPlayer(), "minions.storage-chest.place.success");
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStorageChestBreak(BlockBreakEvent event) {
        if (service.isLinkedStorageChest(event.getBlock())) {
            event.setCancelled(true);
            hex.ui().send(event.getPlayer(), "minions.storage-chest.error.break-linked");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityClick(PlayerInteractAtEntityEvent event) {
        UUID minionId = readMinionId(event.getRightClicked());
        if (minionId == null) return;
        event.setCancelled(true);
        menu.open(event.getPlayer(), minionId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (readMinionId(event.getRightClicked()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (readMinionId(event.getEntity()) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent event) {
        if (readMinionId(event.getEntity()) != null) event.setCancelled(true);
    }

    private UUID readMinionId(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(renderer.minionIdKey(), PersistentDataType.STRING);
        if (raw == null) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException ignored) { return null; }
    }
}

