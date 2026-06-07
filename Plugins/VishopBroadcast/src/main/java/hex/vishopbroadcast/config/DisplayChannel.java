package hex.vishopbroadcast.config;

import java.util.Locale;
import java.util.Optional;

public enum DisplayChannel {
    CHAT,
    ACTION_BAR,
    TITLE;

    public static Optional<DisplayChannel> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        try {
            return Optional.of(DisplayChannel.valueOf(normalized));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}

