package hex.economy.api;

import java.math.BigDecimal;

public record EconomyResult(boolean success, BigDecimal balance, String reason) {
    public static EconomyResult ok(BigDecimal balance) {
        return new EconomyResult(true, balance, "OK");
    }

    public static EconomyResult fail(BigDecimal balance, String reason) {
        return new EconomyResult(false, balance, reason);
    }
}
