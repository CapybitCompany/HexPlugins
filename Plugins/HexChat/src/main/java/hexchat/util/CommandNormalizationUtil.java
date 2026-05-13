package hexchat.util;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class CommandNormalizationUtil {

    private CommandNormalizationUtil() {
    }

    public static Set<String> extractCandidates(String rawInput) {
        Set<String> candidates = new LinkedHashSet<>();
        String token = normalizeToSingleToken(rawInput);
        if (token.isBlank()) {
            return candidates;
        }

        candidates.add(token);

        int namespaceSeparator = token.indexOf(':');
        if (namespaceSeparator > -1 && namespaceSeparator < token.length() - 1) {
            candidates.add(token.substring(namespaceSeparator + 1));
        }

        return candidates;
    }

    public static String normalizeToSingleToken(String rawInput) {
        if (rawInput == null) {
            return "";
        }

        String normalized = rawInput.trim().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        if (normalized.isBlank()) {
            return "";
        }

        int firstSpaceIndex = normalized.indexOf(' ');
        if (firstSpaceIndex > -1) {
            normalized = normalized.substring(0, firstSpaceIndex);
        }

        return normalized.trim();
    }
}
