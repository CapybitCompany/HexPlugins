package hexnpc.shop.model;

import java.util.Locale;

/** Zachowanie po kliknięciu kafla / po zakupie. */
public enum ShopItemActionType {
    DETAILS,
    PLAYER_COMMAND,
    RUN_WORKFLOW,
    NONE;

    public static ShopItemActionType parse(String raw) {
        if (raw == null || raw.isBlank()) return DETAILS;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown action type '" + raw + "'");
        }
    }
}
