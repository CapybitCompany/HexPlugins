package hex.events.registration;

public enum AdmissionStatus {
    REGISTERED,
    WAITING_FOR_START,
    QUEUED,
    ADMITTED,
    PARTICIPATING,
    QUEUE_REFUND_PENDING,
    QUEUE_REFUNDED,
    CANCELLED,
    NO_SHOW,
    LEFT_FORFEITED,
    REJECTED;

    public boolean terminal() {
        return this == QUEUE_REFUNDED || this == CANCELLED || this == NO_SHOW || this == LEFT_FORFEITED || this == REJECTED;
    }

    public boolean blocksRejoin() {
        return terminal() || this == QUEUE_REFUND_PENDING;
    }
}
