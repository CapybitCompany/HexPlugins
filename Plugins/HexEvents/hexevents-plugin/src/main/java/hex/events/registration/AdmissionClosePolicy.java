package hex.events.registration;

/** Pure decision logic used when the admission window becomes permanently closed. */
public final class AdmissionClosePolicy {
    private AdmissionClosePolicy() { }

    public enum Decision { NONE, REFUND_CAPACITY, NO_SHOW }

    public static Decision decide(AdmissionStatus status, boolean online) {
        if (status == null) status = AdmissionStatus.REGISTERED;
        return switch (status) {
            case REGISTERED, WAITING_FOR_START, QUEUED -> online ? Decision.REFUND_CAPACITY : Decision.NO_SHOW;
            case ADMITTED, PARTICIPATING, QUEUE_REFUND_PENDING, QUEUE_REFUNDED,
                    CANCELLED, NO_SHOW, LEFT_FORFEITED, REJECTED -> Decision.NONE;
        };
    }
}
