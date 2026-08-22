package hex.economy.currency;

import hex.economy.api.CurrencyType;
import hex.economy.api.EconomyResult;

import java.math.BigDecimal;
import java.util.UUID;

/** Internal provider abstraction. No external economy types leak through this interface. */
public interface CurrencyProvider {
    CurrencyType type();
    boolean isAvailable();
    BigDecimal getBalance(UUID uuid);
    EconomyResult deposit(UUID uuid, String playerName, BigDecimal amount, String reason);
    EconomyResult withdraw(UUID uuid, String playerName, BigDecimal amount, String reason);
    EconomyResult setBalance(UUID uuid, String playerName, BigDecimal amount, String reason);
    boolean has(UUID uuid, BigDecimal amount);
    String format(BigDecimal amount);
    String displayName();
}
