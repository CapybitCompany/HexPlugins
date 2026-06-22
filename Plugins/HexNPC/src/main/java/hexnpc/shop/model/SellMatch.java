package hexnpc.shop.model;

import java.util.Locale;

public enum SellMatch {
    PLAIN_MATERIAL,
    EXACT_ITEM;

    public static SellMatch parse(String raw, SellMatch fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return SellMatch.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
