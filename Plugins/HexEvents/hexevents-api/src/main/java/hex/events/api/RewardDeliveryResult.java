package hex.events.api;

public record RewardDeliveryResult(Status status, String message) {
    public enum Status { DELIVERED, RETRY_LATER, FAILED_PERMANENT, RECONCILIATION_REQUIRED }
    public static RewardDeliveryResult delivered(){ return new RewardDeliveryResult(Status.DELIVERED, "OK"); }
    public static RewardDeliveryResult retry(String message){ return new RewardDeliveryResult(Status.RETRY_LATER, message); }
    public static RewardDeliveryResult failed(String message){ return new RewardDeliveryResult(Status.FAILED_PERMANENT, message); }
    public static RewardDeliveryResult reconcile(String message){ return new RewardDeliveryResult(Status.RECONCILIATION_REQUIRED, message); }
}
