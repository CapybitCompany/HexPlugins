package hex.events.api;

public record CostOperationResult(boolean success, boolean retryable, String message, CostReceipt receipt) {
    public static CostOperationResult charged(CostReceipt receipt) { return new CostOperationResult(true, false, "OK", receipt); }
    public static CostOperationResult refunded() { return new CostOperationResult(true, false, "OK", null); }
    public static CostOperationResult failed(String message, boolean retryable) { return new CostOperationResult(false, retryable, message, null); }
}
