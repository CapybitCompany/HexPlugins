package hex.events.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Pure countdown formatting/progress logic, intentionally Bukkit-free and unit-testable. */
public final class CountdownBossBarText {
    private static final DateTimeFormatter START_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter START_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT);

    private CountdownBossBarText() { }

    public static String render(String template, String eventId, String eventName, Instant publicStart, ZoneId zone, Duration remaining) {
        long seconds = Math.max(0L, remaining.getSeconds());
        long totalMinutesCeil = seconds == 0 ? 0 : (seconds + 59) / 60;
        return (template == null ? "" : template)
                .replace("{event}", eventName == null ? "" : eventName)
                .replace("{event_id}", eventId == null ? "" : eventId)
                .replace("{time}", format(remaining))
                .replace("{minutes}", Long.toString(totalMinutesCeil))
                .replace("{seconds}", Long.toString(seconds))
                .replace("{start_time}", START_TIME.format(publicStart.atZone(zone)))
                .replace("{start_date}", START_DATE.format(publicStart.atZone(zone)));
    }

    public static String format(Duration duration) {
        long seconds = Math.max(0L, duration.getSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) return String.format(Locale.ROOT, "%d godz. %02d min %02d s", hours, minutes, secs);
        if (minutes > 0) return String.format(Locale.ROOT, "%d min %02d s", minutes, secs);
        return secs + " s";
    }

    /** 1.0 when the boss bar appears, 0.0 at public event start. */
    public static double progress(Duration remaining, Duration showBefore) {
        double total = Math.max(1.0, showBefore.toMillis());
        double value = remaining.toMillis() / total;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
