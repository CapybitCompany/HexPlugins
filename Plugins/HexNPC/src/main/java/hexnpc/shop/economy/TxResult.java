package hexnpc.shop.economy;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Wynik operacji ekonomii. Lustruje hex.economy.api.EconomyResult, dzięki
 * czemu reszta pluginu nigdy nie styka się z typem wynikającym z
 * reflection.
 */
public record TxResult(boolean success, BigDecimal balance, String reason) {

    public static TxResult ok(BigDecimal balance) {
        return new TxResult(true, balance, "");
    }

    public static TxResult fail(String reason) {
        return new TxResult(false, BigDecimal.ZERO, reason == null ? "" : reason);
    }

    /**
     * Zwraca true, gdy porażka wygląda jak „nie wystarcza środków".
     * Łapie kod NOT_ENOUGH_FUNDS z HexEconomy oraz kilka generycznych
     * wariantów — odporne na drobne zmiany nazewnictwa w upstreamie.
     */
    public boolean isInsufficientFunds() {
        if (reason == null) {
            return false;
        }
        String r = reason.toUpperCase(Locale.ROOT);
        return r.contains("NOT_ENOUGH_FUNDS")
                || r.contains("INSUFFICIENT")
                || r.contains("NOT_ENOUGH");
    }

    /** True, gdy odpowiedź przyszła bez backendu ekonomii. */
    public boolean isEconomyUnavailable() {
        return reason != null && reason.equals("economy-unavailable");
    }
}
