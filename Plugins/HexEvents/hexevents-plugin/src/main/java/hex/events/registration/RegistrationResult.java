package hex.events.registration;

public record RegistrationResult(boolean success, String message) {
    public static RegistrationResult ok(String message) { return new RegistrationResult(true, message); }
    public static RegistrationResult fail(String message) { return new RegistrationResult(false, message); }
}
