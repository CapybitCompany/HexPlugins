package hexcasino.config;

import org.bukkit.Material;
import org.bukkit.Particle;

import java.util.List;
import java.util.Map;

public record CasinoConfig(
        Map<String, Machine> machines,
        ParticleSetting idleParticles,
        ParticleSetting occupiedParticles,
        SlotMachine slotMachine,
        WheelOfFortune wheelOfFortune,
        BusDriver busDriver,
        Economy economy,
        Gui gui,
        List<String> initialSymbols,
        List<WinningLine> winningLines,
        List<Symbol> symbols,
        Map<String, Symbol> symbolsById,
        Messages messages,
        Sounds sounds
) {

    public record Machine(
            String id,
            String world,
            Material activationMaterial,
            BlockLocation activationBlock,
            PlayerLocation playerLocation
    ) {
    }

    public record BlockLocation(int x, int y, int z) {
    }

    public record PlayerLocation(double x, double y, double z, float yaw, float pitch) {
    }

    public record ParticleSetting(
            boolean enabled,
            Particle particle,
            int red,
            int green,
            int blue,
            float size,
            int intervalTicks,
            int count,
            double offsetX,
            double offsetY,
            double offsetZ,
            double speed,
            double yOffset
    ) {
    }

    public record SlotMachine(
            List<Double> betPerLineOptions,
            List<Integer> lineOptions,
            double defaultBetPerLine,
            int defaultLines,
            int rollTickInterval,
            int resultSubtitleTicks,
            ExitVelocity exitVelocity,
            Highlight highlight,
            WinAssist winAssist
    ) {
    }

    public record ExitVelocity(boolean enabled, double backwardsStrength, double y) {
    }

    public record Highlight(boolean enabled, int durationTicks, int flashCount) {
    }

    public record WinAssist(boolean enabled, double chancePercent) {
    }

    public record WheelOfFortune(
            Map<String, Machine> machines,
            List<Double> multiplierOptions,
            double defaultMultiplier,
            int spinTickInterval,
            int resultSubtitleTicks,
            ExitVelocity exitVelocity,
            WheelGui gui,
            List<WheelSegment> segments,
            Map<String, WheelSegment> segmentsById,
            List<String> segmentLayout
    ) {
    }

    public record WheelGui(
            String title,
            int size,
            List<Integer> wheelSlots,
            int actionSlot,
            int multiplierSlot,
            int balanceSlot,
            int exitSlot,
            int infoSlot,
            GuiItem filler,
            GuiItem balanceItem,
            GuiItem spinItem,
            GuiItem noFundsItem,
            GuiItem rollingItem,
            GuiItem multiplierItem,
            GuiItem exitItem,
            GuiItem infoItem,
            String infoSegmentLine
    ) {
    }

    public record WheelSegment(
            String id,
            Material material,
            String name,
            List<String> lore,
            double multiplier,
            double chanceWeight
    ) {
    }

    public record BusDriver(
            Map<String, Machine> machines,
            List<Double> multiplierOptions,
            double defaultMultiplier,
            List<Double> roundPayoutMultipliers,
            int resultSubtitleTicks,
            ExitVelocity exitVelocity,
            BusDriverGui gui
    ) {
    }

    public record BusDriverGui(
            String title,
            int size,
            int cardSlot,
            int lowerSlot,
            int higherSlot,
            int cashoutSlot,
            int multiplierSlot,
            int balanceSlot,
            int exitSlot,
            int infoSlot,
            List<Integer> progressSlots,
            List<Integer> suitSlots,
            GuiItem filler,
            GuiItem balanceItem,
            GuiItem startItem,
            GuiItem noFundsItem,
            GuiItem redItem,
            GuiItem blackItem,
            GuiItem lowerItem,
            GuiItem higherItem,
            GuiItem betweenItem,
            GuiItem outsideItem,
            GuiItem heartsItem,
            GuiItem diamondsItem,
            GuiItem clubsItem,
            GuiItem spadesItem,
            GuiItem cashoutItem,
            GuiItem cashoutUnavailableItem,
            GuiItem multiplierItem,
            GuiItem exitItem,
            GuiItem multiplierLockedItem,
            GuiItem infoItem,
            GuiItem progressPendingItem,
            GuiItem progressCompleteItem,
            String infoRoundLine
    ) {
    }

    public record Economy(String balancePlaceholder, String removeCommand, String addCommand) {
    }

    public record Gui(
            String title,
            int size,
            List<Integer> reelSlots,
            int actionSlot,
            int betSlot,
            int balanceSlot,
            int exitSlot,
            int infoSlot,
            GuiItem filler,
            GuiItem balanceItem,
            GuiItem spinAvailableItem,
            GuiItem spinUnavailableItem,
            GuiItem rollingItem,
            GuiItem betItem,
            GuiItem exitItem,
            GuiItem highlightItem,
            GuiItem infoItem,
            String infoSymbolLine
    ) {
    }

    public record GuiItem(
            Material material,
            String name,
            List<String> lore,
            boolean hideTooltip,
            boolean hideAdditionalTooltip,
            String headId,
            String headOwner,
            String headTexture
    ) {
        public GuiItem(Material material, String name, List<String> lore, boolean hideTooltip, boolean hideAdditionalTooltip) {
            this(material, name, lore, hideTooltip, hideAdditionalTooltip, null, null, null);
        }
    }

    public record WinningLine(String id, String name, List<Integer> slots) {
    }

    public record Symbol(
            String id,
            Material material,
            String displayName,
            String legendName,
            List<String> lore,
            double multiplier,
            double chanceWeight,
            String winActionbar,
            List<SoundSetting> winSounds,
            ParticleSetting winParticles
    ) {
    }

    public record Messages(
            String prefix,
            String reloadSuccess,
            String reloadFailed,
            String noPermission,
            String usage,
            String machineBusy,
            String machineUnavailable,
            String alreadyPlaying,
            String noFundsActionbar,
            String economyUnavailableActionbar,
            String spinStartActionbar,
            String loseActionbar,
            String winActionbar,
            String winSubtitle,
            String betChangedActionbar
    ) {

        public String withPrefix(String message) {
            return prefix + message;
        }
    }

    public record Sounds(
            List<SoundSetting> open,
            List<SoundSetting> close,
            List<SoundSetting> noFunds,
            List<SoundSetting> spinStart,
            List<SoundSetting> rollTick,
            List<SoundSetting> columnStop,
            List<SoundSetting> lose,
            List<SoundSetting> winSmall,
            List<SoundSetting> winBig
    ) {
    }

    public record SoundSetting(boolean enabled, String name, float volume, float pitch, int delayTicks) {
    }
}
