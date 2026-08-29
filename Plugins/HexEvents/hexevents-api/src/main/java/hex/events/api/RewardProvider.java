package hex.events.api;

public interface RewardProvider {
    String type();
    default boolean available() { return true; }
    default String unavailableReason() { return ""; }
    /** True for Bukkit/inventory providers. DB/thread-safe providers may return false. */
    default boolean requiresMainThread() { return true; }
    RewardDeliveryResult deliver(RewardContext context, RewardGrant grant);
}
