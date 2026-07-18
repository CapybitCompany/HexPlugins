package hexpvpsmp.config;

public record SafezoneConfig(
        boolean blockEntryWhileCombat,
        int warningCooldownTicks,
        int infoCooldownTicks,
        BarrierConfig barrier
) {
    public SafezoneConfig {
        warningCooldownTicks = Math.max(0, warningCooldownTicks);
        infoCooldownTicks = Math.max(0, infoCooldownTicks);
        barrier = barrier == null ? BarrierConfig.defaults() : barrier;
    }
}
