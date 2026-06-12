package hexpvpsmp.config;

public record RedLineConfig(
        boolean enabled,
        double warningDistance,
        String message
) {
    public RedLineConfig {
        warningDistance = Math.max(0.0D, warningDistance);
        message = message == null ? "" : message;
    }

    public static RedLineConfig disabled() {
        return new RedLineConfig(false, 0.0D, "");
    }
}
