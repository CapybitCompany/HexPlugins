package hexchests;

import hexchests.config.HexChestsConfig;
import hexchests.gui.HexChestsGuiHolder;
import hexchests.gui.OpeningSession;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
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
    private final Map<UUID, OpeningSession> openings = new LinkedHashMap<>();

    public ChestService(JavaPlugin plugin, Supplier<HexChestsConfig> configSupplier, KeyService keyService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.keyService = Objects.requireNonNull(keyService, "keyService");
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
        Optional<String> keyId = keyService.keyId(hand);
        if (keyId.isEmpty()) {
            openPreview(player, chest);
            return;
        }
        if (!keyId.get().equals(chest.requiredKey())) {
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
        Inventory inventory = Bukkit.createInventory(holder, config.gui().size(),
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
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int elapsed;

            @Override
            public void run() {
                Player online = Bukkit.getPlayer(session.playerId());
                if (online == null || !online.isOnline()) {
                    finishSilently(session.playerId());
                    return;
                }
                if (elapsed >= configSupplier.get().gui().opening().durationTicks()) {
                    finishOpening(online, session);
                    return;
                }
                animateOpening(session);
                playOpeningTick(online, configSupplier.get().sounds().openingTick(), elapsed,
                        configSupplier.get().gui().opening().durationTicks());
                elapsed += configSupplier.get().gui().opening().tickIntervalTicks();
            }
        }, 0L, config.gui().opening().tickIntervalTicks());
        session.task(task);
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
        setIndicators(session.inventory(), placeholders(configSupplier.get().chests().get(session.chestId()), session.reward()));
        set(session.inventory(), configSupplier.get().gui().opening().resultSlot(), rewardItem(session.reward(), totalChance(List.of(session.reward()))));
        HexChestsConfig.ChestDefinition chest = configSupplier.get().chests().get(session.chestId());
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
        for (int slot : configSupplier.get().gui().opening().sideSlots()) {
            set(session.inventory(), slot, rewardItem(randomReward(chest.rewards()), totalChance(chest.rewards())));
        }
        setIndicators(session.inventory(), placeholders(chest, null));
        set(session.inventory(), configSupplier.get().gui().opening().rollingSlot(),
                rewardItem(randomReward(chest.rewards()), totalChance(chest.rewards())));
    }

    private void award(Player player, HexChestsConfig.ChestDefinition chest, HexChestsConfig.RewardDefinition reward) {
        Map<String, String> placeholders = placeholders(chest, reward);
        placeholders.put("player", player.getName());
        placeholders.put("uuid", player.getUniqueId().toString());
        if (reward.commands().isEmpty()) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack(reward.material(), reward.amount()));
            if (!leftovers.isEmpty()) {
                player.sendMessage(Text.component(configSupplier.get().messages().withPrefix(configSupplier.get().messages().inventoryFull())));
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
        ItemStack stack = new ItemStack(reward.material(), amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            Map<String, String> placeholders = placeholders(null, reward);
            placeholders.put("chance", chancePercent(reward, totalChance));
            meta.displayName(Text.component(reward.displayName(), placeholders));
            List<String> lore = new ArrayList<>(reward.lore());
            if (!lore.isEmpty()) {
                lore.add("");
            }
            lore.add("&7Szansa: &f{chance}%");
            meta.lore(Text.lore(lore, placeholders));
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String chancePercent(HexChestsConfig.RewardDefinition reward, double totalChance) {
        if (totalChance <= 0.0D) {
            return "0.0";
        }
        return String.format(Locale.US, "%.1f", (reward.chance() / totalChance) * 100.0D);
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
        values.put("reward_name", reward == null ? "" : reward.displayName());
        values.put("amount", reward == null ? "" : Integer.toString(reward.amount()));
        return values;
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
