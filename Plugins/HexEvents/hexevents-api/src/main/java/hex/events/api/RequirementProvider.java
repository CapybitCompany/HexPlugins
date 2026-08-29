package hex.events.api;

public interface RequirementProvider {
    String type();
    RequirementCheck check(PlayerContext player, EventModuleSettings settings);
    default boolean available() { return true; }
    default String unavailableReason() { return ""; }
}
