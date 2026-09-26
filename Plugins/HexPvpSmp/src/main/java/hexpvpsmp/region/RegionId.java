package hexpvpsmp.region;

import java.util.Locale;
import java.util.regex.Pattern;

public record RegionId(String value) {

    private static final Pattern VALID = Pattern.compile("[a-z0-9_\\-]{1,48}");

    public RegionId {
        if (value == null) {
            throw new IllegalArgumentException("RegionId is null");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid RegionId: '" + value + "' (allowed: a-z 0-9 _ -, max 48)");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
