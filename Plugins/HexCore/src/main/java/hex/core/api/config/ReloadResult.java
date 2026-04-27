package hex.core.api.config;

import java.util.List;

public record ReloadResult(boolean success, String message, List<String> validationErrors) {
    public static ReloadResult ok(String msg) { return new ReloadResult(true, msg, List.of()); }
    public static ReloadResult failed(String msg, List<String> errors) { return new ReloadResult(false, msg, errors); }
}
