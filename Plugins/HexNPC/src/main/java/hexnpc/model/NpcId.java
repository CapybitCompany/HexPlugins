package hexnpc.model;

import java.util.Locale;
import java.util.regex.Pattern;

public record NpcId(String value) {

    private static final Pattern VALID = Pattern.compile("[a-z0-9_\\-]{1,32}");

    public NpcId {
        if (value == null) {
            throw new IllegalArgumentException("NpcId is null");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid NpcId: '" + value + "' (allowed: a-z 0-9 _ -, max 32)");
        }
    }

    public static boolean isValid(String raw) {
        if (raw == null) {
            return false;
        }
        return VALID.matcher(raw.trim().toLowerCase(Locale.ROOT)).matches();
    }

    @Override
    public String toString() {
        return value;
    }
}
