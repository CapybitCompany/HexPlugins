package hex.events.api;

public record EventFailure(String code, String message, boolean retryable) {
    public EventFailure {
        code = code == null || code.isBlank() ? "UNKNOWN" : code;
        message = message == null ? "" : message;
    }
}
