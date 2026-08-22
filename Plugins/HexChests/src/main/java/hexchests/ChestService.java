package hexchests;

import hexchests.config.HexChestsConfig;
import hexchests.gui.HexChestsGuiHolder;
import hexchests.gui.OpeningSession;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class ChestService {

    private final JavaPlugin plugin;
    private final Supplier<HexChestsConfig> configSupplier;
    private final KeyService keyService;
    private final CustomItemsBridge customItems;
    private final Map<UUID, OpeningSession> openings = new LinkedHashMap<>();

    public ChestService(JavaPlugin plugin,
                        Supplier<HexChestsConfig> configSupplier,
                        KeyService keyService,
                        CustomItemsBridge customItems) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.keyService = Objects.requireNonNull(keyService, "keyService");
        this.customItems = Objects.requireNonNull(customItems, "customItems");
    }

    public Optional<HexChestsConfig.ChestDefinition> chestAt(Block block) {
        if (block == null) {
            return Optional.empty();
        }
        return chestAt(block.getLocation());
    }

    public Optional<HexChestsConfig.ChestDefinition> chestAt(Location location) {
        HexChestsConfig config = configSupplier.get();
        if (config == null || !config.enabled()) {
            return Optional.empty();
        }
        return config.chests().values().stream()
                .filter(chest -> chest.location().matches(location))
                .findFirst();
    }

    public void handleLeftClick(Player player, HexChestsConfig.ChestDefinition chest) {
        openPreview(player, chest);
    }

    public void handleRightClick(Player player, HexChestsConfig.ChestDefinition chest, ItemStack hand) {
        if (keyService.keyId(hand).isEmpty()) {
            openPreview(player, chest);
            return;
        }
        if (!keyService.isExpectedKey(hand, chest)) {
            player.sendActionBar(Text.component(configSupplier.get().messages().wrongKeyActionbar(), placeholders(chest, null)));
            play(player, configSupplier.get().sounds().wrongKey());
            return;
        }
        startOpening(player, chest);
    }

    public void openPreview(Player player, HexChestsConfig.ChestDefinition chest) {
        HexChestsConfig config = configSupplier.get();
        Map<String, String> placeholders = placeholders(chest, null);
        HexChestsGuiHolder holder = new HexChestsGuiHolder(player.getUniqueId(), chest.id(), HexChestsGuiHolder.Mode.PREVIEW);
        Inventory inventory = Bukkit.createInventory(holder, config.gui().size(),
                Text.legacy(config.gui().previewTitle(), placeholders));
        holder.setInventory(inventory);
        fill(inventory, config.gui().filler(), placeholders);

        double totalChance = totalChance(chest.rewards());
        List<Integer> slots = config.gui().rewardSlots();
        for (int i = 0; i < chest.rewards().size() && i < slots.size(); i++) {
            inventory.setItem(slots.get(i), rewardItem(chest.rewards().get(i), totalChance));
        }
        set(inventory, config.gui().infoSlot(), guiItem(config.gui().infoItem(), placeholders));

        player.openInventory(inventory);
        play(player, config.sounds().preview());
    }

    public boolean startOpening(Player player, HexChestsConfig.ChestDefinition chest) {
        if (openings.containsKey(player.getUniqueId())) {
            return false;
        }
        Optional<HexChestsConfig.RewardDefinition> reward = chooseReward(chest);
        if (reward.isEmpty()) {
            openPreview(player, chest);
            return false;
        }

        keyService.consumeOne(player);
        HexChestsConfig config = configSupplier.get();
        Map<String, String> placeholders = placeholders(chest, null);
        HexChestsGuiHolder holder = new HexChestsGuiHolder(player.getUniqueId(), chest.id(), HexChestsGuiHolder.Mode.OPENING);
        Inventory inventory = Bukkit.createInventory(holder, config.gui().opening().size(),
                Text.legacy(config.gui().openingTitle(), placeholders));
        holder.setInventory(inventory);
        fill(inventory, config.gui().filler(), placeholders);
        for (int slot : config.gui().opening().sideSlots()) {
            set(inventory, slot, guiItem(config.gui().opening().rollingFiller(), placeholders));
        }
        setIndicators(inventory, placeholders);
        player.openInventory(inventory);
        player.sendActionBar(Text.component(config.messages().openingStartedActionbar(), placeholders));

        OpeningSession session = new OpeningSession(player.getUniqueId(), chest.id(), reward.get(), inventory);
        openings.put(player.getUniqueId(), session);
        scheduleOpeningTick(session, 0);
        return true;
    }

    public boolean hasActiveOpening(UUID playerId) {
        OpeningSession session = openings.get(playerId);
        return session != null && !session.finished();
    }

    Optional<OpeningSession> opening(UUID playerId) {
        return Optional.ofNullable(openings.get(playerId));
    }

    void finishActiveOpening(Player player) {
        OpeningSession session = openings.get(player.getUniqueId());
        if (session != null) {
            finishOpening(player, session);
        }
    }

    public void reopenIfOpening(Player player) {
        OpeningSession session = openings.get(player.getUniqueId());
        if (session == null || session.finished()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && hasActiveOpening(player.getUniqueId())) {
                player.openInventory(session.inventory());
            }
        });
    }

    public void remove(Player player) {
        finishSilently(player.getUniqueId());
    }

    public void stop() {
        for (OpeningSession session : openings.values()) {
            if (session.task() != null) {
                session.task().cancel();
            }
        }
        openings.clear();
    }

    void finishOpening(Player player, OpeningSession session) {
        if (session.finished()) {
            return;
        }
        session.finished(true);
        if (session.task() != null) {
            session.task().cancel();
        }
        openings.remove(session.playerId());
        HexChestsConfig.ChestDefinition chest = configSupplier.get().chests().get(session.chestId());
        setIndicators(session.inventory(), placeholders(chest, session.reward()));
        double totalChance = chest == null ? session.reward().chance() : totalChance(chest.rewards());
        set(session.inventory(), configSupplier.get().gui().opening().resultSlot(), rewardItem(session.reward(), totalChance));
        award(player, chest, session.reward());
        Map<String, String> placeholders = placeholders(chest, session.reward());
        player.sendActionBar(Text.component(configSupplier.get().messages().rewardActionbar(), placeholders));
        play(player, configSupplier.get().sounds().reward());
        closeOpeningLater(player.getUniqueId(), session.inventory());
    }

    private void finishSilently(UUID playerId) {
        OpeningSession session = openings.remove(playerId);
        if (session != null && session.task() != null) {
            session.task().cancel();
        }
    }

    private void animateOpening(OpeningSession session) {
        HexChestsConfig.ChestDefinition chest = configSupplier.get().chests().get(session.chestId());
        if (chest == null || chest.rewards().isEmpty()) {
            return;
        }
        double totalChance = totalChance(chest.rewards());
        for (int slot : configSupplier.get().gui().opening().sideSlots()) {
            set(session.inventory(), slot, rewardItem(randomReward(chest.rewards()), totalChance));
        }
        setIndicators(session.inventory(), placeholders(chest, null));
        set(session.inventory(), configSupplier.get().gui().opening().rollingSlot(),
                rewardItem(randomReward(chest.rewards()), totalChance));
    }

    private void scheduleOpeningTick(OpeningSession session, int elapsed) {
        HexChestsConfig.OpeningGui opening = configSupplier.get().gui().opening();
        int duration = opening.durationTicks();
        if (elapsed >= duration) {
            BukkitTask task = Bukkit.getScheduler().runTask(plugin, () -> finishScheduledOpening(session));
            session.task(task);
            return;
        }

        int delay = openingDelay(opening, elapsed, duration);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (session.finished() || openings.get(session.playerId()) != session) {
                return;
            }
            Player online = Bukkit.getPlayer(session.playerId());
            if (online == null || !online.isOnline()) {
                finishSilently(session.playerId());
                return;
            }
            animateOpening(session);
            playOpeningTick(online, configSupplier.get().sounds().openingTick(), elapsed, duration);
            scheduleOpeningTick(session, elapsed + delay);
        }, delay);
        session.task(task);
    }

    private void finishScheduledOpening(OpeningSession session) {
        if (session.finished() || openings.get(session.playerId()) != session) {
            return;
        }
        Player player = Bukkit.getPlayer(session.playerId());
        if (player == null || !player.isOnline()) {
            finishSilently(session.playerId());
            return;
        }
        finishOpening(player, session);
    }

    private int openingDelay(HexChestsConfig.OpeningGui opening, int elapsed, int duration) {
        int min = Math.max(1, opening.tickIntervalTicks());
        int max = Math.max(min, opening.maxTickIntervalTicks());
        float progress = duration <= 0 ? 1.0F : Math.min(1.0F, elapsed / (float) duration);
        return min + Math.round((max - min) * progress * progress);
    }

    private void award(Player player, HexChestsConfig.ChestDefinition chest, HexChestsConfig.RewardDefinition reward) {
        Map<String, String> placeholders = placeholders(chest, reward);
        placeholders.put("player", player.getName());
        placeholders.put("uuid", player.getUniqueId().toString());

        if (reward.customItemId() != null) {
            if (!customItems.give(player, reward.customItemId(), reward.amount())) {
                plugin.getLogger().warning("HexChests: failed to give custom item " + reward.customItemId()
                        + " to " + player.getName() + ".");
                player.sendMessage(Text.component(configSupplier.get().messages()
                        .withPrefix(configSupplier.get().messages().inventoryFull())));
            }
            return;
        }

        if (reward.commands().isEmpty()) {
            boolean fullFit = true;
            if (reward.items().isEmpty()) {
                fullFit = addItem(player, vanillaRewardItem(reward, reward.amount()));
            } else {
                for (HexChestsConfig.RewardItemDefinition item : reward.items()) {
                    fullFit &= addItem(player, vanillaRewardItem(item, item.amount(), reward, reward.items().size() == 1));
                }
            }
            if (!fullFit) {
                player.sendMessage(Text.component(configSupplier.get().messages()
                        .withPrefix(configSupplier.get().messages().inventoryFull())));
            }
            return;
        }

        for (String raw : reward.commands()) {
            String command = Text.apply(raw, placeholders).trim();
            if (command.isEmpty()) {
                continue;
            }
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    private Optional<HexChestsConfig.RewardDefinition> chooseReward(HexChestsConfig.ChestDefinition chest) {
        if (chest.rewards().isEmpty()) {
            return Optional.empty();
        }
        double total = totalChance(chest.rewards());
        if (total <= 0.0D) {
            return Optional.of(chest.rewards().getFirst());
        }
        double roll = ThreadLocalRandom.current().nextDouble(total);
        double cursor = 0.0D;
        for (HexChestsConfig.RewardDefinition reward : chest.rewards()) {
            cursor += reward.chance();
            if (roll <= cursor) {
                return Optional.of(reward);
            }
        }
        return Optional.of(chest.rewards().getLast());
    }

    private HexChestsConfig.RewardDefinition randomReward(List<HexChestsConfig.RewardDefinition> rewards) {
        return rewards.get(ThreadLocalRandom.current().nextInt(rewards.size()));
    }

    private double totalChance(List<HexChestsConfig.RewardDefinition> rewards) {
        double total = 0.0D;
        for (HexChestsConfig.RewardDefinition reward : rewards) {
            total += reward.chance();
        }
        return total;
    }

    private ItemStack rewardItem(HexChestsConfig.RewardDefinition reward, double totalChance) {
        int amount = Math.max(1, Math.min(reward.amount(), reward.material().getMaxStackSize()));
        ItemStack stack = previewStack(reward, amount);
        applyRewardPreviewMeta(stack, reward, totalChance);
        return stack;
    }

    private ItemStack previewStack(HexChestsConfig.RewardDefinition reward, int amount) {
        if (reward.customItemId() != null) {
            return customItems.createItem(reward.customItemId(), amount, null)
                    .orElseGet(() -> configuredRewardItem(reward, amount, true));
        }
        if (!reward.items().isEmpty()) {
            HexChestsConfig.RewardItemDefinition first = reward.items().getFirst();
            int previewAmount = Math.max(1, Math.min(first.amount(), first.material().getMaxStackSize()));
            return configuredRewardItem(first, previewAmount, reward, reward.items().size() == 1, true);
        }
        return configuredRewardItem(reward, amount, true);
    }

    private boolean addItem(Player player, ItemStack stack) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
        return leftovers.isEmpty();
    }

    private ItemStack vanillaRewardItem(HexChestsConfig.RewardDefinition reward, int amount) {
        return configuredRewardItem(reward, amount, false);
    }

    private ItemStack configuredRewardItem(HexChestsConfig.RewardDefinition reward, int amount, boolean preview) {
        ItemStack stack = new ItemStack(reward.material(), Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            Map<String, String> placeholders = placeholders(null, reward);
            String displayName = preview ? reward.displayName() : reward.dropDisplayName();
            List<String> lore = preview ? reward.lore() : reward.dropLore();
            Integer customModelData = preview ? reward.customModelData() : reward.dropCustomModelData();
            boolean changed = false;
            if (displayName != null) {
                meta.displayName(Text.component(displayName, placeholders));
                changed = true;
            }
            if (!lore.isEmpty()) {
                meta.lore(Text.lore(lore, placeholders));
                changed = true;
            }
            if (customModelData != null) {
                meta.setCustomModelData(customModelData);
                changed = true;
            }
            changed |= applyEnchantments(meta, reward.enchantments());
            if (changed) {
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    private ItemStack vanillaRewardItem(HexChestsConfig.RewardItemDefinition item,
                                        int amount,
                                        HexChestsConfig.RewardDefinition reward,
                                        boolean useRewardDisplay) {
        return configuredRewardItem(item, amount, reward, useRewardDisplay, false);
    }

    private ItemStack configuredRewardItem(HexChestsConfig.RewardItemDefinition item,
                                           int amount,
                                           HexChestsConfig.RewardDefinition reward,
                                           boolean useRewardDisplay,
                                           boolean preview) {
        ItemStack stack = new ItemStack(item.material(), Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            Map<String, String> placeholders = placeholders(null, reward);
            String displayName = itemDisplayName(item, reward, useRewardDisplay, preview);
            List<String> lore = itemLore(item, reward, useRewardDisplay, preview);
            Integer customModelData = itemCustomModelData(item, reward, useRewardDisplay, preview);
            boolean changed = false;
            if (displayName != null) {
                meta.displayName(Text.component(displayName, placeholders));
                changed = true;
            }
            if (!lore.isEmpty()) {
                meta.lore(Text.lore(lore, placeholders));
                changed = true;
            }
            if (customModelData != null) {
                meta.setCustomModelData(customModelData);
                changed = true;
            }
            changed |= applyEnchantments(meta, item.enchantments());
            changed |= applySpawnerType(meta, item);
            if (changed) {
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    private String itemDisplayName(HexChestsConfig.RewardItemDefinition item,
                                   HexChestsConfig.RewardDefinition reward,
                                   boolean useRewardDisplay,
                                   boolean preview) {
        if (preview) {
            return item.displayName() != null
                    ? item.displayName()
                    : useRewardDisplay ? reward.displayName() : null;
        }
        return item.dropDisplayName() != null
                ? item.dropDisplayName()
                : useRewardDisplay ? reward.dropDisplayName() : null;
    }

    private List<String> itemLore(HexChestsConfig.RewardItemDefinition item,
                                  HexChestsConfig.RewardDefinition reward,
                                  boolean useRewardDisplay,
                                  boolean preview) {
        if (preview) {
            return item.lore();
        }
        if (!item.dropLore().isEmpty()) {
            return item.dropLore();
        }
        return useRewardDisplay ? reward.dropLore() : List.of();
    }

    private Integer itemCustomModelData(HexChestsConfig.RewardItemDefinition item,
                                        HexChestsConfig.RewardDefinition reward,
                                        boolean useRewardDisplay,
                                        boolean preview) {
        if (preview) {
            return item.customModelData();
        }
        if (item.dropCustomModelData() != null) {
            return item.dropCustomModelData();
        }
        return useRewardDisplay ? reward.dropCustomModelData() : null;
    }

    private boolean applySpawnerType(ItemMeta meta, HexChestsConfig.RewardItemDefinition item) {
        if (item.spawnerType() == null || !(meta instanceof BlockStateMeta blockStateMeta)) {
            return false;
        }
        BlockState state = blockStateMeta.getBlockState();
        if (state instanceof CreatureSpawner spawner) {
            spawner.setSpawnedType(item.spawnerType());
            blockStateMeta.setBlockState(spawner);
            return true;
        }
        return false;
    }

    private void applyRewardPreviewMeta(ItemStack stack, HexChestsConfig.RewardDefinition reward, double totalChance) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        Map<String, String> placeholders = placeholders(null, reward);
        placeholders.put("chance", chancePercent(reward, totalChance));
        if (reward.displayName() != null) {
            meta.displayName(Text.component(reward.displayName(), placeholders));
        }

        List<String> lore = new ArrayList<>();
        if (!reward.lore().isEmpty()) {
            lore.addAll(reward.lore());
        } else if (reward.items().size() > 1) {
            lore.addAll(bundleLore(reward));
        }
        if (!lore.isEmpty()) {
            lore.add("");
        }
        lore.add("&7Szansa: &f{chance}%");
        meta.lore(Text.lore(lore, placeholders));
        applyEnchantments(meta, reward.enchantments());
        stack.setItemMeta(meta);
    }

    private boolean applyEnchantments(ItemMeta meta, Map<Enchantment, Integer> enchantments) {
        if (enchantments.isEmpty()) {
            return false;
        }
        for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
            int level = Math.max(1, entry.getValue());
            if (meta instanceof EnchantmentStorageMeta storage) {
                storage.addStoredEnchant(entry.getKey(), level, true);
            } else {
                meta.addEnchant(entry.getKey(), level, true);
            }
        }
        return true;
    }

    private String chancePercent(HexChestsConfig.RewardDefinition reward, double totalChance) {
        if (totalChance <= 0.0D) {
            return "0.0";
        }
        return String.format(Locale.US, "%.1f", (reward.chance() / totalChance) * 100.0D);
    }

    private List<String> bundleLore(HexChestsConfig.RewardDefinition reward) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Zawiera:");
        for (HexChestsConfig.RewardItemDefinition item : reward.items()) {
            lore.add("&8- &f" + itemLabel(item) + " &7x" + item.amount());
        }
        return lore;
    }

    private String itemLabel(HexChestsConfig.RewardItemDefinition item) {
        if (item.displayName() != null) {
            return item.displayName();
        }
        return item.material().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void fill(Inventory inventory, HexChestsConfig.GuiItem item, Map<String, String> placeholders) {
        ItemStack stack = guiItem(item, placeholders);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, stack.clone());
        }
    }

    private ItemStack guiItem(HexChestsConfig.GuiItem item, Map<String, String> placeholders) {
        ItemStack stack = new ItemStack(item.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.component(item.name(), placeholders));
            meta.lore(Text.lore(item.lore(), placeholders));
            if (item.hideTooltip()) {
                hideTooltip(meta);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void set(Inventory inventory, int slot, ItemStack item) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, item);
    }

    private void setIndicators(Inventory inventory, Map<String, String> placeholders) {
        ItemStack item = guiItem(configSupplier.get().gui().opening().indicatorItem(), placeholders);
        for (int slot : configSupplier.get().gui().opening().indicatorSlots()) {
            set(inventory, slot, item.clone());
        }
    }

    private void closeOpeningLater(UUID playerId, Inventory inventory) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return;
            }
            if (player.getOpenInventory().getTopInventory().equals(inventory)) {
                player.closeInventory();
            }
        }, 40L);
    }

    private Map<String, String> placeholders(HexChestsConfig.ChestDefinition chest,
                                             HexChestsConfig.RewardDefinition reward) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("chest", chest == null ? "" : chest.id());
        values.put("chest_name", chest == null ? "" : chest.displayName());
        values.put("key", chest == null ? "" : chest.requiredKey());
        values.put("reward", reward == null ? "" : reward.id());
        values.put("reward_name", reward == null ? "" : rewardLabel(reward));
        values.put("amount", reward == null ? "" : Integer.toString(reward.amount()));
        return values;
    }

    private String rewardLabel(HexChestsConfig.RewardDefinition reward) {
        if (reward.displayName() != null) {
            return reward.displayName();
        }
        if (reward.customItemId() != null) {
            return reward.customItemId();
        }
        return reward.id();
    }

    private void hideTooltip(ItemMeta meta) {
        meta.addItemFlags(ItemFlag.values());
        try {
            meta.setHideTooltip(true);
        } catch (Throwable ignored) {
            // Keeps compatibility with older test/runtime APIs.
        }
    }

    private void play(Player player, HexChestsConfig.SoundSetting setting) {
        if (!setting.enabled()) {
            return;
        }
        player.playSound(player.getLocation(), setting.name(), setting.volume(), setting.pitch());
    }

    private void playOpeningTick(Player player, HexChestsConfig.SoundSetting setting, int elapsed, int duration) {
        if (!setting.enabled()) {
            return;
        }
        float progress = duration <= 0 ? 0.0F : Math.min(1.0F, elapsed / (float) duration);
        player.playSound(player.getLocation(), setting.name(), setting.volume(), setting.pitch() + (progress * 0.55F));
    }
}
