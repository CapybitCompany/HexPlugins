package hex.events.api;

public record StopResult(boolean success, String message) {
    public static StopResult stopped() { return new StopResult(true, "STOPPED"); }
    public static StopResult failed(String message) { return new StopResult(false, message); }
}
