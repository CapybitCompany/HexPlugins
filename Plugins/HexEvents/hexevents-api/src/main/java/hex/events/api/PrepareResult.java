package hex.events.api;

public record PrepareResult(boolean success, String message) {
    public static PrepareResult ok() { return new PrepareResult(true, "OK"); }
    public static PrepareResult failed(String message) { return new PrepareResult(false, message); }
}
