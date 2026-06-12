package hexpvpsmp.config;

public record SafezoneConfig(
        boolean blockEntryWhileCombat,
        String entryMessage,
        String pvpDenyMessage,
        int warningCooldownTicks
) {
    public SafezoneConfig {
        entryMessage = entryMessage == null ? "" : entryMessage;
        pvpDenyMessage = pvpDenyMessage == null ? "" : pvpDenyMessage;
        warningCooldownTicks = Math.max(0, warningCooldownTicks);
    }
}
