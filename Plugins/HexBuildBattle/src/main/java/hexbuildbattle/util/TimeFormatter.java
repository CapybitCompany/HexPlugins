package hexbuildbattle.util;

public final class TimeFormatter {

    private TimeFormatter() {
    }

    public static String mmss(int seconds) {
        int safe = Math.max(0, seconds);
        return String.format(java.util.Locale.ROOT, "%d:%02d", safe / 60, safe % 60);
    }
}
