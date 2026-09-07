package hex.events.api;

public interface CostProvider {
    String type();
    CostCheck validate(PlayerContext player, EventModuleSettings settings);
    CostOperationResult charge(PlayerContext player, EventModuleSettings settings, String costId, String idempotencyKey);
    CostOperationResult refund(PlayerContext player, CostReceipt receipt, String idempotencyKey);
    default boolean available() { return true; }
    default String unavailableReason() { return ""; }
    /** True when validate/charge/refund touches Bukkit state and must run on the primary thread. */
    default boolean requiresMainThread() { return true; }
}
