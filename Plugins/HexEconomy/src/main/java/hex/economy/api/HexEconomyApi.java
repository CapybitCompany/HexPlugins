package hex.economy.api;

import java.math.BigDecimal;
import java.util.UUID;

public interface HexEconomyApi {
    BigDecimal getBalance(UUID playerUuid);

    EconomyResult deposit(UUID playerUuid, String playerName, BigDecimal amount, String reason);

    EconomyResult withdraw(UUID playerUuid, String playerName, BigDecimal amount, String reason);

    EconomyResult setBalance(UUID playerUuid, String playerName, BigDecimal amount, String reason);

    boolean has(UUID playerUuid, BigDecimal amount);

    String format(BigDecimal amount);

    String currencyName();
}
