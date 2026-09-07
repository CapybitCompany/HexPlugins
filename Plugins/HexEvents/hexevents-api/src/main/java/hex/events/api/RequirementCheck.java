package hex.events.api;

public record RequirementCheck(boolean success, String message) {
    public static RequirementCheck ok() { return new RequirementCheck(true, "OK"); }
    public static RequirementCheck fail(String message) { return new RequirementCheck(false, message); }
}
