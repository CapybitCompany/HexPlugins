package hexnpc.shop.model;

import java.util.Locale;

/** Co gracz otrzymuje po poprawnym pobraniu waluty. */
public enum ShopRewardType {
    ITEM,
    CONSOLE_COMMANDS,
    HEX_CUSTOM_ITEM;

    public static ShopRewardType parse(String raw) {
        if (raw == null || raw.isBlank()) return ITEM;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown reward type '" + raw + "'");
        }
    }
}
