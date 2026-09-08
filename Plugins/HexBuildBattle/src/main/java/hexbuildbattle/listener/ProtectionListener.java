package hexbuildbattle.listener;

import hexbuildbattle.arena.Arena;
import hexbuildbattle.build.BuildSettingsHolder;
import hexbuildbattle.build.BuildSettingsService;
import hexbuildbattle.config.MessageService;
import hexbuildbattle.game.GameManager;
import hexbuildbattle.game.GameState;
import hexbuildbattle.judging.JudgingService;
import hexbuildbattle.protection.MaterialRules;
import hexbuildbattle.rating.RatingService;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ProtectionListener implements Listener {

    private final GameManager gameManager;
    private final MessageService messages;
    private final MaterialRules materialRules;
    private final BuildSettingsService buildSettingsService;
    private final RatingService ratingService;
    private final JudgingService judgingService;

    public ProtectionListener(
            GameManager gameManager,
            MessageService messages,
            MaterialRules materialRules,
            BuildSettingsService buildSettingsService,
            RatingService ratingService,
            JudgingService judgingService
    ) {
        this.gameManager = gameManager;
        this.messages = messages;
        this.materialRules = materialRules;
        this.buildSettingsService = buildSettingsService;
        this.ratingService = ratingService;
        this.judgingService = judgingService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!gameManager.isActiveParticipant(player.getUniqueId()) || gameManager.session().state() != GameState.BUILDING) {
            if (gameManager.controlsPlayer(player)) {
                event.setCancelled(true);
            }
            return;
        }
        if (materialRules.isBlockedItem(event.getItemInHand().getType())
                || materialRules.isBlockedItem(event.getBlockPlaced().getType())
                || !canPlaceInBuildArea(player, event.getBlockPlaced())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!gameManager.isActiveParticipant(player.getUniqueId()) || gameManager.session().state() != GameState.BUILDING) {
            if (gameManager.controlsPlayer(player)) {
                event.setCancelled(true);
            }
            return;
        }
        if (!canBreakInBuildArea(player, event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!gameManager.controlsPlayer(event.getPlayer())) {
            return;
        }
        Block target = event.getBlock().getRelative(event.getBlockFace());
        if (!canPlaceInBuildArea(event.getPlayer(), target)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!gameManager.controlsPlayer(event.getPlayer())) {
            return;
        }
        if (!canModifyBuildRegion(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (judgingService.handleRatingUse(event)) {
            return;
        }

        Player player = event.getPlayer();
        GameState state = gameManager.session().state();
        if (!gameManager.controlsPlayer(player)) {
            return;
        }

        ItemStack item = event.getItem();
        if (buildSettingsService.isCompass(item)) {
            return;
        }

        if (state != GameState.BUILDING) {
            event.setCancelled(true);
            return;
        }

        if (!gameManager.isActiveParticipant(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (item != null && materialRules.isBlockedItem(item.getType())) {
            event.setCancelled(true);
            return;
        }

        Block clicked = event.getClickedBlock();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && clicked != null
                && materialRules.isBlockedInteraction(clicked.getType())
                && !isAllowedPlacementOnBlockedInteraction(player, event, item, clicked)) {
            event.setCancelled(true);
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && item != null && materialRules.isAllowedUtilityItem(item.getType())) {
            BlockFace face = event.getBlockFace();
            Block target = clicked == null ? null : clicked.getRelative(face);
            if (target == null || !canPlaceInBuildArea(player, target)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !gameManager.controlsPlayer(player)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof BuildSettingsHolder) {
            return;
        }

        GameState state = gameManager.session().state();
        if (state == GameState.JUDGING || state == GameState.PRE_JUDGING || state == GameState.RESULTS || state == GameState.RESETTING) {
            event.setCancelled(true);
            return;
        }

        if (state == GameState.BUILDING && gameManager.isActiveParticipant(player.getUniqueId())) {
            if (touchesSlotZero(event) || itemLocked(event.getCurrentItem()) || itemLocked(event.getCursor())) {
                event.setCancelled(true);
                return;
            }
            if ((event.getCursor() != null && materialRules.isBlockedItem(event.getCursor().getType()))
                    || (event.getCurrentItem() != null && materialRules.isBlockedItem(event.getCurrentItem().getType()))) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCreativeInventory(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !gameManager.controlsPlayer(player)) {
            return;
        }
        GameState state = gameManager.session().state();
        if (state == GameState.JUDGING || state == GameState.PRE_JUDGING || state == GameState.RESULTS || state == GameState.RESETTING) {
            event.setCancelled(true);
            return;
        }
        if (state == GameState.BUILDING) {
            ItemStack cursor = event.getCursor();
            ItemStack current = event.getCurrentItem();
            if (touchesSlotZero(event)
                    || itemLocked(cursor)
                    || itemLocked(current)
                    || (cursor != null && materialRules.isBlockedItem(cursor.getType()))
                    || (current != null && materialRules.isBlockedItem(current.getType()))) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !gameManager.controlsPlayer(player)) {
            return;
        }
        GameState state = gameManager.session().state();
        if (state == GameState.JUDGING || state == GameState.PRE_JUDGING || state == GameState.RESULTS || state == GameState.RESETTING) {
            event.setCancelled(true);
            return;
        }
        if (state == GameState.BUILDING
                && (event.getRawSlots().contains(36)
                || itemLocked(event.getCursor())
                || itemLocked(event.getOldCursor()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (gameManager.controlsPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (gameManager.controlsPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player player && gameManager.controlsPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && gameManager.controlsPlayer(player)) {
            event.setCancelled(true);
            gameManager.maintainPlayer(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && gameManager.controlsPlayer(player)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
            player.setSaturation(20.0F);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) {
            return;
        }
        if (!gameManager.controlsPlayer(player)) {
            return;
        }
        GameState state = gameManager.session().state();
        GameMode expected = state == GameState.BUILDING && gameManager.isActiveParticipant(player.getUniqueId())
                ? GameMode.CREATIVE
                : GameMode.ADVENTURE;
        if (event.getNewGameMode() != expected) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!gameManager.controlsPlayer(player)) {
            return;
        }
        if (event.getTo() == null || event.getTo().getWorld() == null) {
            return;
        }
        if (event.getTo().getY() < event.getTo().getWorld().getMinHeight() - 8) {
            gameManager.rescuePlayer(player);
            return;
        }
        if (gameManager.session().state() == GameState.BUILDING && gameManager.isActiveParticipant(player.getUniqueId())) {
            Optional<Arena> arena = gameManager.assignedArena(player.getUniqueId());
            if (arena.isPresent() && !arena.get().moduleRegion().contains(event.getTo())) {
                if (arena.get().moduleRegion().contains(event.getFrom())) {
                    event.setTo(event.getFrom());
                } else {
                    event.setTo(arena.get().moduleRegion().clamp(event.getTo()));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (gameManager.session().state() != GameState.BUILDING
                || !gameManager.isActiveParticipant(player.getUniqueId())
                || event.getTo() == null) {
            return;
        }
        Optional<Arena> arena = gameManager.assignedArena(player.getUniqueId());
        if (arena.isPresent() && !arena.get().moduleRegion().contains(event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!gameManager.controlsPlayer(player)) {
            return;
        }
        event.getDrops().clear();
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        gameManager.rescuePlayer(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (gameManager.controlsPlayer(event.getPlayer())) {
            event.setRespawnLocation(gameManager.rescueLocation(event.getPlayer()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFluidFlow(BlockFromToEvent event) {
        Material material = event.getBlock().getType();
        if (material != Material.WATER && material != Material.LAVA) {
            return;
        }
        Optional<Arena> arena = gameManager.findUsedArenaByBlock(event.getBlock());
        if (arena.isEmpty()) {
            return;
        }
        if (!arena.get().buildRegion().contains(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFireSpread(BlockSpreadEvent event) {
        if (!gameManager.session().state().acceptsQueueForCurrentRound()
                && event.getSource().getType() == Material.FIRE) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBurn(BlockBurnEvent event) {
        if (!gameManager.session().state().acceptsQueueForCurrentRound()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onIgnite(BlockIgniteEvent event) {
        if (gameManager.session().state().acceptsQueueForCurrentRound()) {
            return;
        }
        Player player = event.getPlayer();
        if (player != null && canModifyBuildRegion(player, event.getBlock())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!gameManager.session().state().acceptsQueueForCurrentRound()) {
            event.setCancelled(true);
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!gameManager.session().state().acceptsQueueForCurrentRound()) {
            event.setCancelled(true);
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (gameManager.session().state().acceptsQueueForCurrentRound()) {
            return;
        }
        switch (event.getSpawnReason()) {
            case SPAWNER_EGG, EGG, BUILD_SNOWMAN, BUILD_IRONGOLEM, BUILD_COPPERGOLEM,
                    BUILD_WITHER, DISPENSE_EGG, ENDER_PEARL, NETHER_PORTAL, BUCKET -> event.setCancelled(true);
            default -> {
                if (gameManager.findUsedArenaByLocation(event.getLocation()).isPresent()) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && gameManager.controlsPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && gameManager.controlsPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (gameManager.controlsPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (gameManager.controlsPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        ProjectileSource shooter = event.getEntity().getShooter();
        if (shooter instanceof Player player && gameManager.controlsPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (gameManager.controlsPlayer(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPortal(EntityPortalEvent event) {
        if (!gameManager.session().state().acceptsQueueForCurrentRound()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortalCreate(PortalCreateEvent event) {
        if (!gameManager.session().state().acceptsQueueForCurrentRound()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Optional<Arena> arena = gameManager.findUsedArenaByLocation(event.getBlock().getLocation());
        if (arena.isEmpty()) {
            return;
        }
        if (gameManager.session().state() != GameState.BUILDING
                || !arena.get().buildRegion().contains(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockDispense(BlockDispenseEvent event) {
        if (gameManager.findUsedArenaByBlock(event.getBlock()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (shouldCancelPiston(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (shouldCancelPiston(event.getBlock(), event.getBlocks(), event.getDirection().getOppositeFace())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!gameManager.controlsPlayer(player)) {
            return;
        }
        if (player.isOp()) {
            return;
        }
        String root = commandRoot(event.getMessage());
        if ("lobby".equals(root)) {
            return;
        }
        event.setCancelled(true);
        messages.sendWithPrefix(player, "command.blocked", Map.of());
    }

    private boolean canModifyBuildRegion(Player player, Block block) {
        if (block == null || gameManager.session().state() != GameState.BUILDING
                || !gameManager.isActiveParticipant(player.getUniqueId())) {
            return false;
        }
        Optional<Arena> arena = gameManager.assignedArena(player.getUniqueId());
        return arena.isPresent()
                && arena.get().buildRegion().contains(block);
    }

    private boolean canPlaceInBuildArea(Player player, Block block) {
        if (block == null || gameManager.session().state() != GameState.BUILDING
                || !gameManager.isActiveParticipant(player.getUniqueId())) {
            return false;
        }
        Optional<Arena> arena = gameManager.assignedArena(player.getUniqueId());
        return arena.isPresent()
                && (arena.get().buildRegion().contains(block)
                || arena.get().floorRegion().contains(block)
                || isBelowFloor(arena.get(), block));
    }

    private boolean canBreakInBuildArea(Player player, Block block) {
        if (block == null || gameManager.session().state() != GameState.BUILDING
                || !gameManager.isActiveParticipant(player.getUniqueId())) {
            return false;
        }
        Optional<Arena> arena = gameManager.assignedArena(player.getUniqueId());
        if (arena.isEmpty()) {
            return false;
        }
        return arena.get().buildRegion().contains(block) || arena.get().floorRegion().contains(block);
    }

    private boolean isBelowFloor(Arena arena, Block block) {
        return block.getWorld().equals(arena.floorRegion().world())
                && block.getY() == arena.floorRegion().minY() - 1
                && block.getX() >= arena.floorRegion().minX()
                && block.getX() <= arena.floorRegion().maxX()
                && block.getZ() >= arena.floorRegion().minZ()
                && block.getZ() <= arena.floorRegion().maxZ();
    }

    private String commandRoot(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String command = message.charAt(0) == '/' ? message.substring(1) : message;
        int space = command.indexOf(' ');
        if (space >= 0) {
            command = command.substring(0, space);
        }
        int namespace = command.indexOf(':');
        if (namespace >= 0) {
            command = command.substring(namespace + 1);
        }
        return command.toLowerCase(java.util.Locale.ROOT);
    }

    private boolean shouldCancelPiston(Block piston, List<Block> movedBlocks, BlockFace direction) {
        if (gameManager.session().state() != GameState.BUILDING) {
            return gameManager.findUsedArenaByBlock(piston).isPresent();
        }
        Optional<Arena> arena = gameManager.findUsedArenaByBlock(piston);
        if (arena.isEmpty()) {
            return false;
        }
        for (Block moved : movedBlocks) {
            Block destination = moved.getRelative(direction);
            if (!arena.get().buildRegion().contains(moved) || !arena.get().buildRegion().contains(destination)) {
                return true;
            }
        }
        return !arena.get().buildRegion().contains(piston);
    }

    private boolean touchesSlotZero(InventoryClickEvent event) {
        if (event.getRawSlot() == 36 || event.getSlot() == 0) {
            return true;
        }
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() == 0) {
            return true;
        }
        return event.getAction() == InventoryAction.HOTBAR_SWAP;
    }

    private boolean itemLocked(ItemStack item) {
        return buildSettingsService.isCompass(item) || ratingService.isRatingItem(item) || ratingService.isReportItem(item);
    }

    private boolean isAllowedPlacementOnBlockedInteraction(
            Player player,
            PlayerInteractEvent event,
            ItemStack item,
            Block clicked
    ) {
        if (!player.isSneaking()
                || item == null
                || item.getType().isAir()
                || !item.getType().isBlock()
                || materialRules.isBlockedItem(item.getType())) {
            return false;
        }
        return canPlaceInBuildArea(player, clicked.getRelative(event.getBlockFace()));
    }
}
