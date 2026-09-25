package hexposterunki.rewards;

/**
 * Result of delivering one reward component.
 *
 * <p>The {@code certain} flag is the important one. A database row cannot make a console command
 * or an inventory change part of the same transaction, so the code has to say honestly whether it
 * knows the side effect did not happen:
 * <ul>
 *   <li>{@code delivered} - the component is done, move on;</li>
 *   <li>{@code !delivered && certain} - provably not executed, safe to retry;</li>
 *   <li>{@code !delivered && !certain} - unknown, never retried automatically.</li>
 * </ul>
 */
public record DeliveryOutcome(boolean delivered, boolean certain, String problem) {

    public static DeliveryOutcome ok() {
        return new DeliveryOutcome(true, true, null);
    }

    /** The component provably did not run; retrying it cannot duplicate anything. */
    public static DeliveryOutcome notExecuted(String problem) {
        return new DeliveryOutcome(false, true, problem);
    }

    /** It is unknown whether the component took effect; a human has to decide. */
    public static DeliveryOutcome unknown(String problem) {
        return new DeliveryOutcome(false, false, problem);
    }
}
