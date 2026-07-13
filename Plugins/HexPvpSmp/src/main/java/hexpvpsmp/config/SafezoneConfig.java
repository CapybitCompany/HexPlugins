package hexpvpsmp.config;

public record SafezoneConfig(
        boolean blockEntryWhileCombat,
        int warningCooldownTicks
) {
    public SafezoneConfig {
        warningCooldownTicks = Math.max(0, warningCooldownTicks);
    }
}
