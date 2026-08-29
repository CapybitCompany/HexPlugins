package hex.events.api;

public enum EventStopReason {
    COMPLETED,
    SCHEDULED_END,
    ADMIN_STOP,
    TIMEOUT,
    MODULE_FAILURE,
    SERVER_SHUTDOWN,
    TOO_FEW_PLAYERS
}
