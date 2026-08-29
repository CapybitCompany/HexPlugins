package hex.events.api;

public record EventModuleCapabilities(
        boolean supportsLobby,
        boolean supportsLateJoin,
        boolean supportsAutoTeleport,
        boolean supportsManualWorldEntry,
        boolean supportsRecovery,
        boolean supportsPlayerResults,
        boolean supportsTownResults,
        boolean supportsMultipleConcurrentInstances
) {
    public static EventModuleCapabilities basic() {
        return new EventModuleCapabilities(false, false, false, false, false, false, false, false);
    }
}
