package mysterybox.service;

import mysterybox.config.MysteryBoxConfig;
import mysterybox.model.ResolvedReward;
import mysterybox.util.LegacyTextUtil;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class RewardService {

    private final ItemFactoryService itemFactoryService;
    private final Logger logger;
    private final AtomicReference<State> stateRef = new AtomicReference<>(new State(List.of(), 0));

    public RewardService(ItemFactoryService itemFactoryService, Logger logger, MysteryBoxConfig initialConfig) {
        this.itemFactoryService = Objects.requireNonNull(itemFactoryService, "itemFactoryService");
        this.logger = Objects.requireNonNull(logger, "logger");
        updateConfig(initialConfig);
    }

    public void updateConfig(MysteryBoxConfig config) {
        List<WeightedReward> weightedRewards = new ArrayList<>();
        int cumulative = 0;

        for (MysteryBoxConfig.RewardSettings reward : config.rewards()) {
            if (reward.chance() <= 0) {
                continue;
            }

            ResolvedReward resolvedReward = new ResolvedReward(reward, buildPreviewItem(reward.preview()));
            cumulative += reward.chance();
            weightedRewards.add(new WeightedReward(resolvedReward, cumulative));
        }

        if (weightedRewards.isEmpty()) {
            logger.warning("Brak poprawnych nagród ze szansą > 0. Wrzucam awaryjny reward.");
            MysteryBoxConfig.RewardPreviewSettings preview = new MysteryBoxConfig.RewardPreviewSettings(
                    org.bukkit.Material.COOKIE,
                    "&6Awaryjna nagroda",
                    List.of("&7Sprawdź konfigurację rewards.")
            );
            MysteryBoxConfig.RewardGrantItemSettings grantItem = new MysteryBoxConfig.RewardGrantItemSettings(
                    true,
                    MysteryBoxConfig.RewardItemPreset.CUSTOM,
                    org.bukkit.Material.COOKIE,
                    1,
                    "&6Awaryjna nagroda",
                    List.of("&7Sprawdź konfigurację rewards.")
            );
            MysteryBoxConfig.RewardGrantSettings grant = new MysteryBoxConfig.RewardGrantSettings(grantItem, List.of());
            MysteryBoxConfig.RewardSettings fallback = new MysteryBoxConfig.RewardSettings(
                    "fallback",
                    100,
                    "&6Awaryjna nagroda",
                    preview,
                    grant
            );
            ResolvedReward resolved = new ResolvedReward(fallback, buildPreviewItem(preview));
            weightedRewards = List.of(new WeightedReward(resolved, 100));
            cumulative = 100;
        }

        stateRef.set(new State(List.copyOf(weightedRewards), cumulative));
    }

    public ResolvedReward rollReward() {
        State state = stateRef.get();
        int random = ThreadLocalRandom.current().nextInt(1, state.totalChance + 1);

        for (WeightedReward weightedReward : state.weightedRewards) {
            if (random <= weightedReward.cumulativeChance()) {
                return weightedReward.reward();
            }
        }

        return state.weightedRewards.get(state.weightedRewards.size() - 1).reward();
    }

    public ItemStack createGrantedItem(MysteryBoxConfig.RewardGrantItemSettings itemSettings) {
        return switch (itemSettings.preset()) {
            case MYSTERY_BOX -> itemFactoryService.createMysteryBoxItem(itemSettings.amount());
            case VIP_VOUCHER -> itemFactoryService.createVipVoucherItem(itemSettings.amount());
            case CUSTOM -> itemFactoryService.createCustomItem(itemSettings);
        };
    }

    private ItemStack buildPreviewItem(MysteryBoxConfig.RewardPreviewSettings previewSettings) {
        ItemStack preview = new ItemStack(previewSettings.material(), 1);
        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(LegacyTextUtil.colorize(previewSettings.name()));
            meta.setLore(LegacyTextUtil.colorize(previewSettings.lore()));
            preview.setItemMeta(meta);
        }
        return preview;
    }

    private record State(
            List<WeightedReward> weightedRewards,
            int totalChance
    ) {
    }

    private record WeightedReward(
            ResolvedReward reward,
            int cumulativeChance
    ) {
    }
}
