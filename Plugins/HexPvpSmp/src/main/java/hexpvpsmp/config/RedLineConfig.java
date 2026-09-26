package hexpvpsmp.config;

public record RedLineConfig(
        boolean enabled,
        double warningDistance
) {
    public RedLineConfig {
        warningDistance = Math.max(0.0D, warningDistance);
    }

    public static RedLineConfig disabled() {
        return new RedLineConfig(false, 0.0D);
    }
}
