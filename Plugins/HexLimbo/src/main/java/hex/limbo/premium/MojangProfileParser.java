package hex.limbo.premium;

import java.util.Optional;
import java.util.UUID;

/**
 * Minimal JSON extractor for the two fields we care about in
 * {@code https://api.mojang.com/users/profiles/minecraft/<name>} responses:
 * {@code "id":"<32 hex chars>"} and {@code "name":"<canonical>"}.
 *
 * <p>The parser tokenises the body just enough to read string literals, including correctly
 * skipping over escape sequences. We intentionally avoid pulling in a full JSON dependency for two
 * fields, but the logic is exhaustively tested in {@code MojangProfileParserTest}.
 */
public final class MojangProfileParser {

    public record Profile(Optional<UUID> id, Optional<String> name) {}

    private MojangProfileParser() {}

    public static Profile parse(String body) {
        if (body == null) {
            return new Profile(Optional.empty(), Optional.empty());
        }
        String id = extractString(body, "id");
        String name = extractString(body, "name");
        return new Profile(toDashedUuid(id), Optional.ofNullable(name));
    }

    private static String extractString(String body, String fieldName) {
        int searchStart = 0;
        while (searchStart < body.length()) {
            int keyStart = findStringLiteral(body, searchStart, fieldName);
            if (keyStart < 0) {
                return null;
            }
            int afterKey = keyStart + fieldName.length() + 2; // past closing quote
            int colon = skipWhitespace(body, afterKey);
            if (colon < 0 || body.charAt(colon) != ':') {
                searchStart = afterKey;
                continue;
            }
            int valueStart = skipWhitespace(body, colon + 1);
            if (valueStart < 0 || body.charAt(valueStart) != '"') {
                searchStart = colon + 1;
                continue;
            }
            return readString(body, valueStart);
        }
        return null;
    }

    /** Returns the index of the opening quote of a string literal equal to {@code key}, or -1. */
    private static int findStringLiteral(String body, int from, String key) {
        for (int i = from; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '"') {
                continue;
            }
            int end = scanStringEnd(body, i);
            if (end < 0) {
                return -1;
            }
            String literal = unescape(body.substring(i + 1, end));
            if (key.equals(literal)) {
                return i;
            }
            i = end;
        }
        return -1;
    }

    /** Reads a JSON string starting at the opening quote and returns its decoded value, or null. */
    private static String readString(String body, int openingQuoteIndex) {
        int end = scanStringEnd(body, openingQuoteIndex);
        if (end < 0) {
            return null;
        }
        return unescape(body.substring(openingQuoteIndex + 1, end));
    }

    /** Returns the index of the matching closing quote, accounting for escapes. */
    private static int scanStringEnd(String body, int openingQuoteIndex) {
        boolean escape = false;
        for (int i = openingQuoteIndex + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String body, int from) {
        for (int i = from; i < body.length(); i++) {
            if (!Character.isWhitespace(body.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 4 < raw.length()) {
                        try {
                            int codePoint = Integer.parseInt(raw.substring(i + 1, i + 5), 16);
                            out.append((char) codePoint);
                            i += 4;
                        } catch (NumberFormatException ex) {
                            out.append("\\u");
                        }
                    } else {
                        out.append("\\u");
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    public static Optional<UUID> toDashedUuid(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.length() == 36 && trimmed.charAt(8) == '-') {
            try {
                return Optional.of(UUID.fromString(trimmed));
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }
        if (trimmed.length() != 32) {
            return Optional.empty();
        }
        for (int i = 0; i < 32; i++) {
            char c = trimmed.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return Optional.empty();
            }
        }
        String dashed = trimmed.substring(0, 8) + "-" + trimmed.substring(8, 12) + "-"
                + trimmed.substring(12, 16) + "-" + trimmed.substring(16, 20) + "-" + trimmed.substring(20);
        try {
            return Optional.of(UUID.fromString(dashed));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
