package hex.events.api;

public record StartResult(boolean success, String message) {
    public static StartResult started() { return new StartResult(true, "STARTED"); }
    public static StartResult failed(String message) { return new StartResult(false, message); }
}
