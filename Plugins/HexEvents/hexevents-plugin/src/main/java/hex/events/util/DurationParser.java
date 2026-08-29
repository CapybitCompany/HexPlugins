package hex.events.util;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {
    private static final Pattern TOKEN = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)", Pattern.CASE_INSENSITIVE);
    private DurationParser() { }

    public static Duration parse(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("0") || value.equals("0s") || value.equals("0m")) return Duration.ZERO;
        Matcher matcher = TOKEN.matcher(value);
        long millis = 0;
        int end = 0;
        boolean matched = false;
        while (matcher.find()) {
            if (!value.substring(end, matcher.start()).isBlank()) throw new IllegalArgumentException("Niepoprawny duration: " + raw);
            long n = Long.parseLong(matcher.group(1));
            millis = Math.addExact(millis, switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "ms" -> n;
                case "s" -> Math.multiplyExact(n, 1_000L);
                case "m" -> Math.multiplyExact(n, 60_000L);
                case "h" -> Math.multiplyExact(n, 3_600_000L);
                case "d" -> Math.multiplyExact(n, 86_400_000L);
                default -> throw new IllegalArgumentException("Niepoprawny duration: " + raw);
            });
            end = matcher.end();
            matched = true;
        }
        if (!matched || !value.substring(end).isBlank()) throw new IllegalArgumentException("Niepoprawny duration: " + raw);
        return Duration.ofMillis(millis);
    }
}
