package hex.events.api;

public enum EventState {
    SCHEDULED,
    REGISTRATION_OPEN,
    PREPARING,
    LOBBY,
    RUNNING,
    FINISHING,
    FINISHED,
    CANCELLED,
    FAILED;

    public boolean terminal() {
        return this == FINISHED || this == CANCELLED || this == FAILED;
    }
}
