package hex.economy.model;

import java.math.BigDecimal;
import java.util.UUID;

public record EconomyTopEntry(UUID playerUuid, String playerName, BigDecimal balance) {
    public static EconomyTopEntry empty() {
        return new EconomyTopEntry(null, "-", null);
    }
}
