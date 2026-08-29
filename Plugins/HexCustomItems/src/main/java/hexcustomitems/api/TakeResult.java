package hexcustomitems.api;

public record TakeResult(boolean success, int removed, String reason) {
    public static TakeResult ok(int removed) { return new TakeResult(true, removed, "OK"); }
    public static TakeResult fail(String reason) { return new TakeResult(false, 0, reason); }
}
