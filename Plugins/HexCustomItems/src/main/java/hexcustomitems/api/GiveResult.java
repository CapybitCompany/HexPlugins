package hexcustomitems.api;

public record GiveResult(boolean success, int given, String reason) {
    public static GiveResult ok(int given) { return new GiveResult(true, given, "OK"); }
    public static GiveResult fail(String reason) { return new GiveResult(false, 0, reason); }
}
