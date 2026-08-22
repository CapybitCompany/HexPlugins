package hex.minions.listener;

import hex.minions.config.StorageChestDefinition;
import hex.core.api.HexApi;
import hex.minions.menu.MinionMenu;
import hex.minions.render.MinionRenderer;
import hex.minions.service.MinionItemFactory;
import hex.minions.service.MinionService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
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


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLinkedStorageChestInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null || !service.isLinkedStorageChest(clicked)) return;
        event.setCancelled(true);
        Optional<hex.minions.model.MinionInstance> minion = service.adjacentMinion(clicked.getLocation());
        if (minion.isEmpty()) return;
        if (!service.canAccessMinion(event.getPlayer(), minion.get().id())) {
            hex.ui().send(event.getPlayer(), "minions.error.not-member");
            return;
        }
        menu.openStorageChest(event.getPlayer(), minion.get().id());
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
        target.setYaw(player.getLocation().getYaw());
        service.place(player, target, item).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            hex.ui().send(player, result.messageKey(), result.tokens());
            if (result.success()) item.setAmount(item.getAmount() - 1);
        }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStorageChestItemPlace(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null || service.isLinkedStorageChest(clicked)) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        Optional<String> storageId = service.storageChestIdForItem(item);
        if (storageId.isEmpty() || "iron_uranium".equalsIgnoreCase(storageId.get())) return;
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setCancelled(true);
        Block target = clicked.getRelative(event.getBlockFace());
        Optional<hex.minions.model.MinionInstance> minion = service.adjacentMinion(target.getLocation());
        if (minion.isEmpty()) { hex.ui().send(player, "minions.storage-chest.error.special-required"); return; }
        if (!target.getType().isAir()) { hex.ui().send(player, "minions.storage-chest.error.no-space-left"); return; }
        if (!service.canAccessMinion(player, minion.get().id())) { hex.ui().send(player, "minions.error.not-member"); return; }
        if (!service.validateOrAdoptProgressionItem(item, minion.get().townUuid(), "minion_storage")) {
            player.sendMessage("§cTen magazyn należy do innego miasta.");
            return;
        }
        if (service.hasAdjacentStorageChest(minion.get(), target.getLocation())) { hex.ui().send(player, "minions.storage-chest.error.already-has"); return; }
        if (service.touchesOtherChest(target.getLocation())) { hex.ui().send(player, "minions.storage-chest.error.next-to-chest"); return; }
        StorageChestDefinition definition = service.storageChestDefinitionForItem(item).orElse(null);
        if (definition == null || definition.placedMaterial() == null || definition.placedMaterial() == Material.AIR || !definition.placedMaterial().isBlock()) {
            plugin.getLogger().severe("Storage item ma nieznane lub nieprawidłowe storage_chest_id: " + storageId.get());
            hex.ui().send(player, "minions.storage-chest.error.special-required");
            return;
        }
        Material previous = target.getType();
        try {
            target.setType(definition.placedMaterial(), false);
            BlockData data = target.getBlockData();
            if (data instanceof Directional directional) {
                directional.setFacing(player.getFacing().getOppositeFace()); target.setBlockData(directional, false);
            }
            service.markPlacedStorageChest(target, item);
            if (!itemFactory.readStorageChestBlockId(target).map(definition.id()::equalsIgnoreCase).orElse(false)) {
                throw new IllegalStateException("storage PDC was not persisted");
            }
        } catch (Throwable throwable) {
            itemFactory.unmarkStorageChestBlock(target);
            target.setType(previous, false);
            plugin.getLogger().warning("Placement storage miniona został wycofany: " + throwable.getMessage());
            hex.ui().send(player, "minions.storage-chest.error.special-required");
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            if (item.getAmount() <= 1) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            else item.setAmount(item.getAmount() - 1);
        }
        hex.ui().send(player, "minions.storage-chest.place.success");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStorageChestPlace(BlockPlaceEvent event) {
        Material material = event.getBlockPlaced().getType();
        if (material != Material.CHEST && material != Material.TRAPPED_CHEST) return;
        service.adjacentMinion(event.getBlockPlaced().getLocation()).ifPresent(minion -> {
            boolean uraniumChest = service.specialItems().readSpecialItemId(event.getItemInHand())
                    .map("iron_uranium_chest"::equalsIgnoreCase).orElse(false)
                    || itemFactory.readStorageChestItem(event.getItemInHand())
                    .map(data -> "iron_uranium".equalsIgnoreCase(data.id())).orElse(false);
            if (uraniumChest) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cSkrzynka uranowa służy wyłącznie do przechowywania wzbogaconego uranu i nie może rozszerzać magazynu miniona.");
                return;
            }
            if (!service.canAccessMinion(event.getPlayer(), minion.id())) {
                event.setCancelled(true);
                hex.ui().send(event.getPlayer(), "minions.error.not-member");
                return;
            }
            if (!service.isValidStorageChestItem(event.getItemInHand())) {
                event.setCancelled(true);
                hex.ui().send(event.getPlayer(), "minions.storage-chest.error.special-required");
                return;
            }
            if (!service.validateOrAdoptProgressionItem(event.getItemInHand(), minion.townUuid(), "minion_storage")) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cTen magazyn należy do innego miasta.");
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
        if (!service.canAccessMinion(event.getPlayer(), minionId)) {
            hex.ui().send(event.getPlayer(), "minions.error.not-member");
            return;
        }
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

