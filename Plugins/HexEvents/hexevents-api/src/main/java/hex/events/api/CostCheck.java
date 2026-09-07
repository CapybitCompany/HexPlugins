package hex.events.api;

public record CostCheck(boolean success, String message) {
    public static CostCheck ok() { return new CostCheck(true, "OK"); }
    public static CostCheck fail(String message) { return new CostCheck(false, message); }
}
