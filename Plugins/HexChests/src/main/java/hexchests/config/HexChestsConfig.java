package hexchests.config;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;

public record HexChestsConfig(
        boolean enabled,
        Messages messages,
        Sounds sounds,
        Gui gui,
        TestKeys testKeys,
        Map<String, ChestDefinition> chests
) {

    public record Messages(
            String prefix,
            String noPermission,
            String playerOnly,
            String usage,
            String reloadSuccess,
            String reloadFailed,
            String keyGiven,
            String wrongKeyActionbar,
            String openingStartedActionbar,
            String rewardActionbar,
            String inventoryFull
    ) {
        public String withPrefix(String message) {
            return prefix + message;
        }
    }

    public record Sounds(SoundSetting preview, SoundSetting wrongKey, SoundSetting openingTick, SoundSetting reward) {
    }

    public record SoundSetting(boolean enabled, String name, float volume, float pitch) {
    }

    public record Gui(
            int size,
            String previewTitle,
            String openingTitle,
            GuiItem filler,
            List<Integer> rewardSlots,
            int infoSlot,
            GuiItem infoItem,
            OpeningGui opening
    ) {
    }

    public record OpeningGui(
            int durationTicks,
            int tickIntervalTicks,
            int rollingSlot,
            int resultSlot,
            List<Integer> sideSlots,
            List<Integer> indicatorSlots,
            GuiItem indicatorItem,
            GuiItem rollingFiller
    ) {
    }

    public record GuiItem(
            Material material,
            String name,
            List<String> lore,
            boolean hideTooltip
    ) {
    }

    public record TestKeys(boolean enabled, Map<String, KeyDefinition> keys) {
    }

    public record KeyDefinition(
            String id,
            String command,
            Material material,
            Integer customModelData,
            String displayName,
            List<String> lore
    ) {
    }

    public record ChestDefinition(
            String id,
            String displayName,
            BlockLocation location,
            Material blockMaterial,
            String requiredKey,
            Material previewMaterial,
            List<RewardDefinition> rewards
    ) {
    }

    public record BlockLocation(String world, int x, int y, int z) {
        public boolean matches(Location location) {
            if (location == null || location.getWorld() == null) {
                return false;
            }
            return location.getWorld().getName().equals(world)
                    && location.getBlockX() == x
                    && location.getBlockY() == y
                    && location.getBlockZ() == z;
        }
    }

    public record RewardDefinition(
            String id,
            Material material,
            String displayName,
            int amount,
            double chance,
            List<String> lore,
            List<String> commands
    ) {
    }
}
