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

import java.lang.reflect.Method;
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
        ClaimState state = rewardService.state(player);
        Map<String, String> placeholders = rewardService.placeholders(player, state);
        Optional<ResolvedDailyReward> reward = rewardService.currentReward(state);
        DailyRewardsGuiHolder holder = new DailyRewardsGuiHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, config.gui().size(),
                Text.legacy(config.gui().title(), placeholders));
        holder.setInventory(inventory);

        ItemStack filler = item(config.gui().filler(), placeholders, reward);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }

        if (state.available()) {
            set(inventory, config.gui().items().available(), placeholders, reward);
            set(inventory, config.gui().items().statusAvailable(), placeholders, reward);
        } else {
            set(inventory, config.gui().items().claimed(), placeholders, reward);
            set(inventory, config.gui().items().statusClaimed(), placeholders, reward);
        }
        set(inventory, config.gui().items().info(), placeholders, reward);
        set(inventory, config.gui().items().close(), placeholders, reward);

        player.openInventory(inventory);
        play(player, config.sounds().open());
    }

    public void handleClick(Player player, int rawSlot) {
        DailyRewardsConfig config = configSupplier.get();
        if (rawSlot == config.gui().items().close().slot()) {
            player.closeInventory();
            return;
        }
        if (rawSlot != config.gui().items().available().slot()
                && rawSlot != config.gui().items().claimed().slot()) {
            return;
        }

        ClaimResult result = rewardService.claim(player);
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
            case DISABLED -> player.sendMessage(Text.component(config.messages().withPrefix(config.messages().disabled())));
            case NO_REWARD -> {
                player.sendMessage(Text.component(config.messages().withPrefix(config.messages().noRewardConfigured())));
                play(player, config.sounds().unavailable());
            }
            case ERROR -> player.sendMessage(Text.component(config.messages().withPrefix(config.messages().claimError())));
        }
    }

    private void set(Inventory inventory,
                     DailyRewardsConfig.GuiItem item,
                     Map<String, String> placeholders,
                     Optional<ResolvedDailyReward> reward) {
        if (!item.enabled() || item.slot() < 0 || item.slot() >= inventory.getSize()) {
            return;
        }
        inventory.setItem(item.slot(), item(item, placeholders, reward));
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
            Method method = meta.getClass().getMethod("setHideTooltip", boolean.class);
            method.invoke(meta, true);
        } catch (ReflectiveOperationException ignored) {
            // Older Paper APIs do not expose a full tooltip hide flag.
        }
    }

    private void play(Player player, DailyRewardsConfig.SoundSetting setting) {
        if (!setting.enabled()) {
            return;
        }
        player.playSound(player.getLocation(), setting.name(), setting.volume(), setting.pitch());
    }
}
