package hex.endevent.util;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class TimeTextFormatter {
    private static final Locale PL = Locale.forLanguageTag("pl-PL");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", PL);
    private static final DateTimeFormatter FRIENDLY = DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy 'o' HH:mm", PL);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", PL);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", PL);

    private TimeTextFormatter() { }

    public static String dateTime(ZonedDateTime value) { return value.format(DATE_TIME); }
    public static String friendly(ZonedDateTime value) { return value.format(FRIENDLY); }
    public static String date(ZonedDateTime value) { return value.format(DATE); }
    public static String time(ZonedDateTime value) { return value.format(TIME); }

    public static String remaining(ZonedDateTime now, ZonedDateTime end) {
        return duration(Duration.between(now, end));
    }

    public static String relative(ZonedDateTime now, ZonedDateTime future) {
        Duration d = Duration.between(now, future);
        if (d.isNegative() || d.isZero()) return "teraz";
        long seconds = d.getSeconds();
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) return "za " + days + "d " + hours + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        if (hours > 0) return "za " + hours + "h " + minutes + "m";
        if (minutes > 0) return "za " + minutes + "m";
        return "za " + Math.max(1, seconds) + "s";
    }

    public static String duration(Duration duration) {
        long seconds = Math.max(0L, duration.getSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) return hours + "h " + minutes + "m " + secs + "s";
        if (minutes > 0) return minutes + "m " + secs + "s";
        return secs + "s";
    }
}
