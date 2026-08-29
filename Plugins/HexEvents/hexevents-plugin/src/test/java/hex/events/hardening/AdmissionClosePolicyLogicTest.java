package hex.events.hardening;

import hex.events.registration.AdmissionClosePolicy;
import hex.events.registration.AdmissionStatus;

public final class AdmissionClosePolicyLogicTest {
    public static void main(String[] args) {
        check(AdmissionClosePolicy.decide(AdmissionStatus.QUEUED, true) == AdmissionClosePolicy.Decision.REFUND_CAPACITY,
                "online queued player must get capacity refund");
        check(AdmissionClosePolicy.decide(AdmissionStatus.QUEUED, false) == AdmissionClosePolicy.Decision.NO_SHOW,
                "offline queued player must not get capacity refund");
        check(AdmissionClosePolicy.decide(AdmissionStatus.WAITING_FOR_START, true) == AdmissionClosePolicy.Decision.REFUND_CAPACITY,
                "online race at admission close must be safely refunded");
        check(AdmissionClosePolicy.decide(AdmissionStatus.PARTICIPATING, true) == AdmissionClosePolicy.Decision.NONE,
                "participant is never capacity-refunded");
        check(AdmissionClosePolicy.decide(AdmissionStatus.LEFT_FORFEITED, true) == AdmissionClosePolicy.Decision.NONE,
                "forfeited player never gets refund");
        System.out.println("AdmissionClosePolicyLogicTest OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
