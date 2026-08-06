package hex.quests.listener;

import hex.core.api.messaging.HexMessageData;
import hex.core.api.trigger.GameTrigger;
import hex.core.api.trigger.TriggerService;
import hex.quests.HexQuestsPlugin;
import hex.quests.api.QuestContentResolver;
import hex.quests.tracking.PlayerPlacedBlockTracker;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Bisected;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class QuestGameplayListener implements Listener {
    private static final Set<CreatureSpawnEvent.SpawnReason> FARMED_SPAWN_REASONS = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.SPAWNER,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
            CreatureSpawnEvent.SpawnReason.CUSTOM,
            CreatureSpawnEvent.SpawnReason.COMMAND,
            CreatureSpawnEvent.SpawnReason.DISPENSE_EGG,
            CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM,
            CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN,
            CreatureSpawnEvent.SpawnReason.BUILD_WITHER
    );

    private final HexQuestsPlugin plugin;
    private final TriggerService triggers;
    private final PlayerPlacedBlockTracker placedBlocks;
    private final NamespacedKey spawnReasonKey;
    private final Map<UUID, WalkBuffer> walkBuffers = new HashMap<>();
    private final Set<BlockKey> recentTallFlowerBreaks = new HashSet<>();
    private BukkitTask walkFlushTask;

    public QuestGameplayListener(HexQuestsPlugin plugin, TriggerService triggers, PlayerPlacedBlockTracker placedBlocks) {
        this.plugin = plugin;
        this.triggers = triggers;
        this.placedBlocks = placedBlocks;
        this.spawnReasonKey = new NamespacedKey(plugin, "quest_spawn_reason");
    }

    public void start() {
        walkFlushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushWalkAll, 40L, 40L);
    }

    public void stop() {
        flushWalkAll();
        if (walkFlushTask != null) walkFlushTask.cancel();
        walkFlushTask = null;
        walkBuffers.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        boolean playerPlaced = placedBlocks.isPlayerPlaced(block);
        placedBlocks.remove(block);
        Player player = event.getPlayer();
        if (!valid(player) || !plugin.isTriggerActive("minecraft.block.break")) return;

        Material material = block.getType();
        List<String> tags = new ArrayList<>();
        if (Tag.LOGS.isTagged(material)) tags.add("LOGS");
        if (Tag.FLOWERS.isTagged(material)) {
            tags.add("FLOWERS");
            if (block.getBlockData() instanceof Bisected bisected) {
                Block base = bisected.getHalf() == Bisected.Half.TOP
                        ? block.getRelative(BlockFace.DOWN)
                        : block;
                BlockKey key = new BlockKey(base.getWorld().getUID(), base.getX(), base.getY(), base.getZ());
                if (!recentTallFlowerBreaks.add(key)) return;
                Bukkit.getScheduler().runTask(plugin, () -> recentTallFlowerBreaks.remove(key));
            }
        }
        boolean mature = block.getBlockData() instanceof Ageable ageable && ageable.getAge() == ageable.getMaximumAge();
        publish(player, "minecraft.block.break", builder -> builder
                .put("material", material.name())
                .putStringList("tags", tags)
                .put("player-placed", playerPlaced)
                .put("mature", mature)
                .put("amount", 1L));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player player) || !valid(player)) return;
        if (!plugin.isTriggerActive("minecraft.entity.breed")) return;
        publish(player, "minecraft.entity.breed", builder -> builder
                .put("entity-type", event.getEntityType().name())
                .put("amount", 1L));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !valid(event.getPlayer())) return;
        if (!plugin.isTriggerActive("minecraft.fish.catch")) return;
        if (!(event.getCaught() instanceof Item item)) return;
        ItemStack stack = item.getItemStack();
        publish(event.getPlayer(), "minecraft.fish.catch", builder -> {
            builder.put("item-type", stack.getType().name()).put("amount", stack.getAmount());
            String customId = customItemId(stack);
            if (customId != null) builder.put("custom-item-id", customId);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        Player player = event.getPlayer();
        if (!valid(player) || event.getItemAmount() <= 0) return;
        if (!plugin.isTriggerActive("minecraft.furnace.extract")) return;
        publish(player, "minecraft.furnace.extract", builder -> builder
                .put("item-type", event.getItemType().name())
                .put("amount", event.getItemAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !valid(player)) return;
        if (!plugin.isTriggerActive("minecraft.item.craft")) return;
        Material result = event.getRecipe().getResult().getType();
        if (result.isAir()) return;
        int before = countInPlayerPossession(player, result);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            int crafted = Math.max(0, countInPlayerPossession(player, result) - before);
            if (crafted <= 0) return;
            publish(player, "minecraft.item.craft", builder -> builder
                    .put("item-type", result.name())
                    .put("amount", crafted));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        Player player = event.getPlayer();
        if (!valid(player) || !plugin.isTriggerActive("minecraft.player.trade")) return;
        publish(player, "minecraft.player.trade", builder -> builder
                .put("villager-type", event.getVillager().getType().name())
                .put("amount", 1L));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        event.getEntity().getPersistentDataContainer().set(spawnReasonKey, PersistentDataType.STRING,
                event.getSpawnReason().name());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        Player player = event.getEntity().getKiller();
        if (player == null || !valid(player) || !plugin.isTriggerActive("minecraft.entity.kill")) return;
        Entity entity = event.getEntity();
        String spawnReasonName = entity.getPersistentDataContainer().get(spawnReasonKey, PersistentDataType.STRING);
        CreatureSpawnEvent.SpawnReason spawnReason = parseSpawnReason(spawnReasonName);
        boolean eligible = spawnReason == null || !FARMED_SPAWN_REASONS.contains(spawnReason);
        if (entity.hasMetadata("NPC") || entity.getScoreboardTags().contains("NPC")
                || entity.getScoreboardTags().contains("quest-ignore")) eligible = false;
        if (entity.getType() == EntityType.ENDER_DRAGON || entity.getType() == EntityType.WITHER
                || entity.getType() == EntityType.WARDEN) eligible = false;
        QuestContentResolver resolver = plugin.contentResolver();
        String customMobId = resolver == null ? null : resolver.customMobId(entity);
        if (resolver != null) {
            boolean resolverEligible = resolver.isQuestEligibleMob(entity);
            // A registered custom-mob provider may explicitly admit its own plugin-spawned mobs.
            eligible = customMobId != null && !customMobId.isBlank()
                    ? resolverEligible
                    : eligible && resolverEligible;
        }
        boolean finalEligible = eligible;
        String finalCustomMobId = customMobId;

        Map<Material, Integer> drops = new HashMap<>();
        for (ItemStack drop : event.getDrops()) {
            drops.merge(drop.getType(), drop.getAmount(), Integer::sum);
        }
        publish(player, "minecraft.entity.kill", builder -> {
            builder.put("entity-type", entity.getType().name())
                    .put("monster", entity instanceof Monster)
                    .put("eligible-kill", finalEligible)
                    .put("spawn-reason", spawnReason == null ? "UNKNOWN" : spawnReason.name())
                    .put("amount", 1L);
            for (Map.Entry<Material, Integer> drop : drops.entrySet()) {
                builder.put("drop-" + drop.getKey().name().toLowerCase(Locale.ROOT).replace('_', '-'), drop.getValue());
            }
            if (finalCustomMobId != null && !finalCustomMobId.isBlank()) {
                builder.put("custom-mob-id", finalCustomMobId);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        Player player = event.getPlayer();
        if (!valid(player) || !plugin.isTriggerActive("minecraft.player.walk")) return;
        if (event.getTo() == null || event.getFrom().getWorld() != event.getTo().getWorld()) return;
        if (player.isInsideVehicle() || player.isGliding() || player.isFlying() || player.isSwimming()) return;
        double dx = event.getTo().getX() - event.getFrom().getX();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= 0.0 || distance > 10.0) return;

        LocalDate date = plugin.today();
        WalkBuffer buffer = walkBuffers.computeIfAbsent(player.getUniqueId(), ignored -> new WalkBuffer(date));
        if (!buffer.date.equals(date)) {
            flushWalk(player, buffer);
            buffer.date = date;
            buffer.distance = 0.0;
        }
        buffer.distance += distance;
        if (buffer.distance >= 25.0) flushWalk(player, buffer);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        WalkBuffer buffer = walkBuffers.remove(event.getPlayer().getUniqueId());
        if (buffer != null) flushWalk(event.getPlayer(), buffer);
    }

    public void flushWalkAll() {
        for (Map.Entry<UUID, WalkBuffer> entry : new ArrayList<>(walkBuffers.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) flushWalk(player, entry.getValue());
        }
    }

    private void flushWalk(Player player, WalkBuffer buffer) {
        long wholeBlocks = (long) Math.floor(buffer.distance);
        if (wholeBlocks <= 0) return;
        buffer.distance -= wholeBlocks;
        LocalDate eventDate = buffer.date;
        publish(player, "minecraft.player.walk", builder -> builder
                .put("amount", wholeBlocks)
                .put("event-date", eventDate.toString()));
    }

    private void publish(Player player, String triggerId, Consumer<HexMessageData.Builder> data) {
        HexMessageData.Builder builder = GameTrigger.dataBuilder()
                .put("player-uuid", player.getUniqueId().toString())
                .put("player-name", player.getName())
                .put("event-date", plugin.today().toString());
        data.accept(builder);
        triggers.publish(GameTrigger.of(triggerId, plugin.getName(), builder.build()));
    }

    private boolean valid(Player player) {
        GameMode mode = player.getGameMode();
        return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR
                && !player.hasMetadata("NPC") && !player.getScoreboardTags().contains("NPC");
    }

    private int countInPlayerPossession(Player player, Material material) {
        int amount = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) amount += stack.getAmount();
        }
        ItemStack cursor = player.getItemOnCursor();
        if (cursor.getType() == material) amount += cursor.getAmount();
        return amount;
    }

    private String customItemId(ItemStack stack) {
        QuestContentResolver resolver = plugin.contentResolver();
        return resolver == null ? null : resolver.customItemId(stack);
    }

    private CreatureSpawnEvent.SpawnReason parseSpawnReason(String value) {
        if (value == null || value.isBlank()) return null;
        try { return CreatureSpawnEvent.SpawnReason.valueOf(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private record BlockKey(UUID worldUuid, int x, int y, int z) {}

    private static final class WalkBuffer {
        private LocalDate date;
        private double distance;

        private WalkBuffer(LocalDate date) {
            this.date = date;
        }
    }
}
