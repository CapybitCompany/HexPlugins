package hex.parkour.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Locale;

public final class DurationFormats {
    private DurationFormats() {
    }

    public static String formatNanos(long nanos, String pattern) {
        return formatMillis(Duration.ofNanos(Math.max(0L, nanos)).toMillis(), pattern);
    }

    public static String formatMillis(long millis, String pattern) {
        Duration duration = Duration.ofMillis(Math.max(0L, millis));
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).toSeconds();
        long millisPart = duration.minusMinutes(minutes).minusSeconds(seconds).toMillis();
        String safePattern = pattern == null || pattern.isBlank() ? "mm:ss.SSS" : pattern;
        return safePattern
                .replace("mm", String.format(Locale.ROOT, "%02d", minutes))
                .replace("ss", String.format(Locale.ROOT, "%02d", seconds))
                .replace("SSS", String.format(Locale.ROOT, "%03d", millisPart));
    }

    public static long parseMillis(String raw) {
        if (raw == null) return -1L;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return -1L;
        try {
            if (value.endsWith("ms")) {
                return Long.parseLong(value.substring(0, value.length() - 2).trim());
            }
            if (value.endsWith("s")) {
                return decimalMillis(value.substring(0, value.length() - 1).trim(), 1_000L);
            }
            if (value.endsWith("m")) {
                return decimalMillis(value.substring(0, value.length() - 1).trim(), 60_000L);
            }
            if (value.contains(":")) {
                return parseColonTime(value);
            }
            return Long.parseLong(value);
        } catch (RuntimeException error) {
            return -1L;
        }
    }

    private static long parseColonTime(String value) {
        String[] parts = value.split(":");
        if (parts.length != 2 && parts.length != 3) return -1L;
        long hours = 0L;
        long minutes;
        String secondsPart;
        if (parts.length == 3) {
            hours = Long.parseLong(parts[0]);
            minutes = Long.parseLong(parts[1]);
            secondsPart = parts[2];
        } else {
            minutes = Long.parseLong(parts[0]);
            secondsPart = parts[1];
        }
        BigDecimal seconds = new BigDecimal(secondsPart);
        BigDecimal millis = seconds.multiply(BigDecimal.valueOf(1_000L))
                .add(BigDecimal.valueOf(minutes * 60_000L))
                .add(BigDecimal.valueOf(hours * 3_600_000L));
        return millis.setScale(0, RoundingMode.DOWN).longValueExact();
    }

    private static long decimalMillis(String value, long multiplier) {
        return new BigDecimal(value)
                .multiply(BigDecimal.valueOf(multiplier))
                .setScale(0, RoundingMode.DOWN)
                .longValueExact();
    }
}
