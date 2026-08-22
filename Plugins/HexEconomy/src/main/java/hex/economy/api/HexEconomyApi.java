package hex.economy.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public HexEconomy contract.
 *
 * The original seven methods are intentionally preserved byte-for-byte at the
 * descriptor level. New multi-currency overloads are default methods so a
 * third-party implementation compiled against the legacy interface does not
 * acquire new abstract obligations.
 */
public interface HexEconomyApi {
    // ===== LEGACY API — DO NOT REMOVE OR CHANGE =====
    BigDecimal getBalance(UUID playerUuid);

    EconomyResult deposit(UUID playerUuid, String playerName, BigDecimal amount, String reason);

    EconomyResult withdraw(UUID playerUuid, String playerName, BigDecimal amount, String reason);

    EconomyResult setBalance(UUID playerUuid, String playerName, BigDecimal amount, String reason);

    boolean has(UUID playerUuid, BigDecimal amount);

    String format(BigDecimal amount);

    String currencyName();

    // ===== MULTI-CURRENCY EXTENSIONS =====
    default BigDecimal getBalance(UUID playerUuid, CurrencyType currency) {
        if (currency == CurrencyType.MONEY) {
            return getBalance(playerUuid);
        }
        throw new UnsupportedOperationException("Currency unavailable in this HexEconomyApi provider: " + currency);
    }

    default boolean has(UUID playerUuid, CurrencyType currency, BigDecimal amount) {
        return currency == CurrencyType.MONEY && has(playerUuid, amount);
    }

    default EconomyResult deposit(UUID playerUuid, String playerName, CurrencyType currency, BigDecimal amount, String reason) {
        if (currency == CurrencyType.MONEY) {
            return deposit(playerUuid, playerName, amount, reason);
        }
        return EconomyResult.fail(BigDecimal.ZERO, "CURRENCY_UNAVAILABLE");
    }

    default EconomyResult withdraw(UUID playerUuid, String playerName, CurrencyType currency, BigDecimal amount, String reason) {
        if (currency == CurrencyType.MONEY) {
            return withdraw(playerUuid, playerName, amount, reason);
        }
        return EconomyResult.fail(BigDecimal.ZERO, "CURRENCY_UNAVAILABLE");
    }

    default EconomyResult setBalance(UUID playerUuid, String playerName, CurrencyType currency, BigDecimal amount, String reason) {
        if (currency == CurrencyType.MONEY) {
            return setBalance(playerUuid, playerName, amount, reason);
        }
        return EconomyResult.fail(BigDecimal.ZERO, "CURRENCY_UNAVAILABLE");
    }

    default String format(CurrencyType currency, BigDecimal amount) {
        if (currency == CurrencyType.MONEY) {
            return format(amount);
        }
        return amount == null ? "0" : amount.toPlainString();
    }

    default String currencyName(CurrencyType currency) {
        if (currency == CurrencyType.MONEY) {
            return currencyName();
        }
        return currency == null ? "unknown" : currency.name();
    }


    // ===== INTEGER-ONLY HEX_COINS CONVENIENCE API =====
    // These default methods preserve ABI safety for third-party legacy implementations.
    default int getHexCoins(UUID playerUuid) {
        return getBalance(playerUuid, CurrencyType.HEX_COINS).intValueExact();
    }

    default boolean hasHexCoins(UUID playerUuid, int amount) {
        if (amount <= 0) return false;
        return has(playerUuid, CurrencyType.HEX_COINS, BigDecimal.valueOf(amount));
    }

    default EconomyResult depositHexCoins(UUID playerUuid, String playerName, int amount, String reason) {
        return deposit(playerUuid, playerName, CurrencyType.HEX_COINS, BigDecimal.valueOf(amount), reason);
    }

    default EconomyResult withdrawHexCoins(UUID playerUuid, String playerName, int amount, String reason) {
        return withdraw(playerUuid, playerName, CurrencyType.HEX_COINS, BigDecimal.valueOf(amount), reason);
    }

    default EconomyResult setHexCoins(UUID playerUuid, String playerName, int amount, String reason) {
        return setBalance(playerUuid, playerName, CurrencyType.HEX_COINS, BigDecimal.valueOf(amount), reason);
    }

    default boolean isCurrencyAvailable(CurrencyType currency) {
        return currency == CurrencyType.MONEY;
    }
}
