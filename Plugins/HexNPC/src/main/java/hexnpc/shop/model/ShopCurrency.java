package hexnpc.shop.model;

import java.util.Locale;

/**
 * Waluta używana przez pojedynczy sklep HexNPC.
 * Brak pola {@code currency} w shops.yml zawsze oznacza {@link #MONEY}.
 */
public enum ShopCurrency {
    MONEY,
    HEX_COINS;

    public static ShopCurrency parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return MONEY;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid currency '" + raw + "' (allowed: MONEY, HEX_COINS)");
        }
    }

    public boolean requiresWholeUnitPrices() {
        return this == HEX_COINS;
    }
}
