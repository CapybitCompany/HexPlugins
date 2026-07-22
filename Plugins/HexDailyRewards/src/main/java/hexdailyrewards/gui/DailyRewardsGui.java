package hexdailyrewards.gui;

import hexdailyrewards.ClaimResult;
import hexdailyrewards.ClaimState;
import hexdailyrewards.DailyRewardService;
import hexdailyrewards.ResolvedDailyReward;
import hexdailyrewards.Text;
import hexdailyrewards.config.DailyRewardsConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class DailyRewardsGui {

    private final JavaPlugin plugin;
    private final DailyRewardService rewardService;
    private final Supplier<DailyRewardsConfig> configSupplier;

    public DailyRewardsGui(JavaPlugin plugin,
                           DailyRewardService rewardService,
                           Supplier<DailyRewardsConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.rewardService = Objects.requireNonNull(rewardService, "rewardService");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public void open(Player player) {
        DailyRewardsConfig config = configSupplier.get();
        ClaimState primaryState = rewardService.state(player);
        Map<String, String> primaryPlaceholders = rewardService.placeholders(player, primaryState);
        Optional<ResolvedDailyReward> primaryReward = rewardService.currentReward(primaryState);
        DailyRewardsGuiHolder holder = new DailyRewardsGuiHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, config.gui().size(),
                Text.legacy(config.gui().title(), primaryPlaceholders));
        holder.setInventory(inventory);

        ItemStack filler = item(config.gui().filler(), primaryPlaceholders, primaryReward);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }

        for (DailyRewardsConfig.RewardGroup group : rewardService.rewardGroups()) {
            ClaimState groupState = rewardService.state(player, group.id());
            Map<String, String> placeholders = rewardService.placeholders(player, groupState);
            Optional<ResolvedDailyReward> reward = rewardService.currentReward(group.id(), groupState.today());
            fillFrame(inventory, group, placeholders, reward);
        }

        for (DailyRewardsConfig.RewardGroup group : rewardService.rewardGroups()) {
            ClaimState groupState = rewardService.state(player, group.id());
            Map<String, String> placeholders = rewardService.placeholders(player, groupState);
            Optional<ResolvedDailyReward> reward = rewardService.currentReward(group.id(), groupState.today());
            DailyRewardsConfig.GuiItem template;
            if (!rewardService.canAccess(player, group)) {
                template = config.gui().items().locked();
            } else if (groupState.available()) {
                template = config.gui().items().available();
            } else {
                template = config.gui().items().claimed();
            }
            set(inventory, template, group.slot(), placeholders, reward);
        }

        if (primaryState.available()) {
            set(inventory, config.gui().items().statusAvailable(), primaryPlaceholders, primaryReward);
        } else {
            set(inventory, config.gui().items().statusClaimed(), primaryPlaceholders, primaryReward);
        }
        set(inventory, config.gui().items().info(), primaryPlaceholders, primaryReward);
        set(inventory, config.gui().items().close(), primaryPlaceholders, primaryReward);

        player.openInventory(inventory);
        play(player, config.sounds().open());
    }

    public void handleClick(Player player, int rawSlot) {
        DailyRewardsConfig config = configSupplier.get();
        if (config.gui().items().close().enabled() && rawSlot == config.gui().items().close().slot()) {
            player.closeInventory();
            return;
        }

        Optional<DailyRewardsConfig.RewardGroup> clickedGroup = rewardService.rewardGroups().stream()
                .filter(group -> group.slot() == rawSlot)
                .findFirst();
        if (clickedGroup.isEmpty()) {
            return;
        }

        ClaimResult result = rewardService.claim(player, clickedGroup.get().id());
        switch (result.status()) {
            case CLAIMED -> {
                Map<String, String> placeholders = rewardService.placeholders(player, result.state());
                player.sendMessage(Text.component(config.messages().withPrefix(config.messages().rewardClaimedChat()), placeholders));
                player.sendActionBar(Text.component(config.messages().rewardClaimedActionbar(), placeholders));
                play(player, config.sounds().claim());
                if (config.reward().closeGuiAfterClaim()) {
                    player.closeInventory();
                } else if (config.reward().refreshGuiAfterClaim()) {
                    Bukkit.getScheduler().runTask(plugin, () -> open(player));
                }
            }
            case UNAVAILABLE -> {
                Map<String, String> placeholders = rewardService.placeholders(player, result.state());
                player.sendActionBar(Text.component(config.messages().alreadyClaimedActionbar(), placeholders));
                play(player, config.sounds().unavailable());
            }
            case LOCKED -> {
                Map<String, String> placeholders = rewardService.placeholders(player, result.state());
                player.sendActionBar(Text.component(config.messages().rewardLockedActionbar(), placeholders));
                play(player, config.sounds().unavailable());
            }
            case DISABLED -> player.sendMessage(Text.component(config.messages().withPrefix(config.messages().disabled())));
            case NO_REWARD -> {
                player.sendMessage(Text.component(config.messages().withPrefix(config.messages().noRewardConfigured())));
                play(player, config.sounds().unavailable());
            }
            case ERROR -> player.sendMessage(Text.component(config.messages().withPrefix(config.messages().claimError())));
        }
    }

    private void fillFrame(Inventory inventory,
                           DailyRewardsConfig.RewardGroup group,
                           Map<String, String> placeholders,
                           Optional<ResolvedDailyReward> reward) {
        DailyRewardsConfig.GuiItem frame = new DailyRewardsConfig.GuiItem(
                true,
                0,
                group.frameMaterial(),
                false,
                group.frameName(),
                group.frameLore(),
                group.frameHideTooltip()
        );
        ItemStack stack = item(frame, placeholders, reward);
        int rows = inventory.getSize() / 9;
        for (int column : group.frameColumns()) {
            if (column < 1 || column > 9) {
                continue;
            }
            int columnIndex = column - 1;
            for (int row = 0; row < rows; row++) {
                inventory.setItem((row * 9) + columnIndex, stack.clone());
            }
        }
    }

    private void set(Inventory inventory,
                     DailyRewardsConfig.GuiItem item,
                     Map<String, String> placeholders,
                     Optional<ResolvedDailyReward> reward) {
        set(inventory, item, item.slot(), placeholders, reward);
    }

    private void set(Inventory inventory,
                     DailyRewardsConfig.GuiItem item,
                     int slot,
                     Map<String, String> placeholders,
                     Optional<ResolvedDailyReward> reward) {
        if (!item.enabled() || slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, item(item, placeholders, reward));
    }

    private ItemStack item(DailyRewardsConfig.GuiItem config,
                           Map<String, String> placeholders,
                           Optional<ResolvedDailyReward> reward) {
        ItemStack stack = new ItemStack(material(config, reward));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.component(config.name(), placeholders));
            List<net.kyori.adventure.text.Component> lore = lore(config, placeholders, reward);
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            if (config.hideTooltip()) {
                hideTooltip(meta);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private org.bukkit.Material material(DailyRewardsConfig.GuiItem config, Optional<ResolvedDailyReward> reward) {
        if (config.useRewardMaterial() && reward.isPresent()) {
            return reward.get().definition().material();
        }
        return config.material();
    }

    private List<net.kyori.adventure.text.Component> lore(DailyRewardsConfig.GuiItem config,
                                                          Map<String, String> placeholders,
                                                          Optional<ResolvedDailyReward> reward) {
        List<net.kyori.adventure.text.Component> out = new ArrayList<>();
        for (String line : config.lore()) {
            if (line != null && line.trim().equals("{reward_lore}")) {
                if (reward.isPresent()) {
                    out.addAll(Text.lore(reward.get().definition().lore(), placeholders));
                }
                continue;
            }
            out.add(Text.component(line, placeholders));
        }
        return out;
    }

    private void hideTooltip(ItemMeta meta) {
        meta.addItemFlags(ItemFlag.values());
        try {
            meta.setHideTooltip(true);
        } catch (Throwable ignored) {
            // Keeps test/runtime compatibility if an older API is used accidentally.
        }
    }

    private void play(Player player, DailyRewardsConfig.SoundSetting setting) {
        if (!setting.enabled()) {
            return;
        }
        player.playSound(player.getLocation(), setting.name(), setting.volume(), setting.pitch());
    }
}
