package hexchat.util;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsowanie i formatowanie czasów trwania wyciszeń.
 * Obsługiwane jednostki: s (sekundy), m (minuty), h (godziny), d (dni), w (tygodnie).
 * Wartość permanentna: "perm", "permanent", "0" lub pusty ciąg.
 */
public final class DurationUtil {

    /** Sentinel oznaczający wyciszenie permanentne. */
    public static final long PERMANENT = 0L;

    private static final Pattern TOKEN = Pattern.compile("(\\d+)\\s*([smhdw])");

    private DurationUtil() {
    }

    /**
     * @return czas trwania w milisekundach, {@link #PERMANENT} dla wyciszenia stałego,
     * lub pusty Optional, gdy wejście jest niepoprawne.
     */
    public static Optional<Long> parseMillis(String rawInput) {
        if (rawInput == null) {
            return Optional.empty();
        }

        String input = rawInput.trim().toLowerCase(Locale.ROOT);
        if (input.isEmpty() || input.equals("perm") || input.equals("permanent") || input.equals("0")) {
            return Optional.of(PERMANENT);
        }

        Matcher matcher = TOKEN.matcher(input);
        long totalMillis = 0L;
        int matchedChars = 0;
        try {
            while (matcher.find()) {
                matchedChars += matcher.group(0).length();
                long amount = Long.parseLong(matcher.group(1));
                long unitMillis = switch (matcher.group(2)) {
                    case "s" -> 1000L;
                    case "m" -> 60_000L;
                    case "h" -> 3_600_000L;
                    case "d" -> 86_400_000L;
                    case "w" -> 604_800_000L;
                    default -> 0L;
                };
                // Overflow-bezpiecznie: zbyt duże wartości -> odrzucamy zamiast rzucać wyjątkiem wyżej.
                totalMillis = Math.addExact(totalMillis, Math.multiplyExact(amount, unitMillis));
            }
        } catch (NumberFormatException | ArithmeticException ex) {
            return Optional.empty();
        }

        // Odrzuć wejście, które zawiera nierozpoznane fragmenty (np. "10x", "abc").
        String withoutSpaces = input.replace(" ", "");
        if (matchedChars != withoutSpaces.length() || totalMillis <= 0L) {
            return Optional.empty();
        }

        return Optional.of(totalMillis);
    }

    /**
     * Formatuje pozostały czas do postaci czytelnej, np. "2h 30m".
     * Dla wartości &lt;= 0 zwraca "0s".
     */
    public static String formatRemaining(long millis) {
        if (millis <= 0L) {
            return "0s";
        }

        long seconds = millis / 1000L;
        long weeks = seconds / 604_800L;
        seconds %= 604_800L;
        long days = seconds / 86_400L;
        seconds %= 86_400L;
        long hours = seconds / 3_600L;
        seconds %= 3_600L;
        long minutes = seconds / 60L;
        seconds %= 60L;

        StringBuilder builder = new StringBuilder();
        appendUnit(builder, weeks, "w");
        appendUnit(builder, days, "d");
        appendUnit(builder, hours, "h");
        appendUnit(builder, minutes, "m");
        appendUnit(builder, seconds, "s");

        return builder.length() == 0 ? "0s" : builder.toString().trim();
    }

    private static void appendUnit(StringBuilder builder, long value, String suffix) {
        if (value > 0L) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(value).append(suffix);
        }
    }
}
