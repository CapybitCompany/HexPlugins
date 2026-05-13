package mysterybox.model;

import mysterybox.config.MysteryBoxConfig;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

public record ResolvedReward(
        MysteryBoxConfig.RewardSettings settings,
        ItemStack previewItem
) {
    public ResolvedReward {
        settings = Objects.requireNonNull(settings, "settings");
        previewItem = Objects.requireNonNull(previewItem, "previewItem");
    }
}
