package hex.economy.currency;

import hex.core.api.HexApi;
import hex.economy.api.CurrencyType;
import hex.economy.api.EconomyResult;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** HEX_COINS provider. HEX_COINS are strictly integer-only. */
public final class HexCoinsCurrencyProvider implements CurrencyProvider {
    private final HexCoinsBackend backend;
    private final HexApi hexApi;
    private volatile String displayName;
    private volatile String format;

    public HexCoinsCurrencyProvider(HexCoinsBackend backend, HexApi hexApi, String displayName, String format) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.hexApi = hexApi;
        configure(displayName, format);
    }

    public void configure(String displayName, String format) {
        this.displayName = displayName == null || displayName.isBlank() ? "HexCoins" : displayName;
        this.format = format == null || format.isBlank() ? "{amount} {currency}" : format;
    }

    @Override public CurrencyType type() { return CurrencyType.HEX_COINS; }
    @Override public boolean isAvailable() { return backend.isAvailable(); }

    /** Native integer API for HEX_COINS. */
    public int getIntBalance(UUID uuid) {
        requireUuid(uuid);
        if (!isAvailable()) throw new IllegalStateException("HEX_COINS currency is unavailable");
        return backend.getBalance(uuid).orElse(0);
    }

    @Override public BigDecimal getBalance(UUID uuid) { return BigDecimal.valueOf(getIntBalance(uuid)); }

    public Optional<CurrencyAccount> findAccountByName(String playerName) {
        return isAvailable() ? backend.findAccountByName(playerName) : Optional.empty();
    }

    public boolean hasInt(UUID uuid, int amount) {
        return isAvailable() && amount > 0 && getIntBalance(uuid) >= amount;
    }

    public EconomyResult depositInt(UUID uuid, String playerName, int amount, String reason) {
        if (amount <= 0) return EconomyResult.fail(balanceAsBigDecimal(uuid), "INVALID_AMOUNT");
        return fromBackend(uuid, backend.deposit(uuid, playerName, amount, reason));
    }

    public EconomyResult withdrawInt(UUID uuid, String playerName, int amount, String reason) {
        if (amount <= 0) return EconomyResult.fail(balanceAsBigDecimal(uuid), "INVALID_AMOUNT");
        return fromBackend(uuid, backend.withdraw(uuid, playerName, amount, reason));
    }

    public EconomyResult setIntBalance(UUID uuid, String playerName, int amount, String reason) {
        requireUuid(uuid);
        if (!isAvailable()) return EconomyResult.fail(BigDecimal.ZERO, "CURRENCY_UNAVAILABLE");
        if (amount < 0) return EconomyResult.fail(balanceAsBigDecimal(uuid), "NEGATIVE_DISABLED");
        return fromBackend(uuid, backend.set(uuid, playerName, amount, reason));
    }

    @Override
    public EconomyResult deposit(UUID uuid, String playerName, BigDecimal amount, String reason) {
        Integer integerAmount = positiveInt(amount);
        return integerAmount == null
                ? EconomyResult.fail(balanceAsBigDecimal(uuid), "INVALID_AMOUNT")
                : depositInt(uuid, playerName, integerAmount, reason);
    }

    @Override
    public EconomyResult withdraw(UUID uuid, String playerName, BigDecimal amount, String reason) {
        Integer integerAmount = positiveInt(amount);
        return integerAmount == null
                ? EconomyResult.fail(balanceAsBigDecimal(uuid), "INVALID_AMOUNT")
                : withdrawInt(uuid, playerName, integerAmount, reason);
    }

    @Override
    public EconomyResult setBalance(UUID uuid, String playerName, BigDecimal amount, String reason) {
        Integer integerAmount = nonNegativeInt(amount);
        if (integerAmount == null) {
            String failure = amount != null && amount.compareTo(BigDecimal.ZERO) < 0 ? "NEGATIVE_DISABLED" : "INVALID_AMOUNT";
            return EconomyResult.fail(balanceAsBigDecimal(uuid), failure);
        }
        return setIntBalance(uuid, playerName, integerAmount, reason);
    }

    @Override
    public boolean has(UUID uuid, BigDecimal amount) {
        Integer integerAmount = positiveInt(amount);
        return integerAmount != null && hasInt(uuid, integerAmount);
    }

    @Override
    public String format(BigDecimal amount) {
        int value = amount == null ? 0 : requireWholeInt(amount);
        return format.replace("{amount}", Integer.toString(value)).replace("{currency}", displayName);
    }

    @Override public String displayName() { return displayName; }

    private EconomyResult fromBackend(UUID uuid, BackendTransactionResult result) {
        int balance = result.balance();
        BigDecimal publicBalance = BigDecimal.valueOf(balance);
        if (result.success()) {
            writeThrough(uuid);
            return EconomyResult.ok(publicBalance);
        }
        return EconomyResult.fail(publicBalance, result.reason() == null ? "PROVIDER_ERROR" : result.reason());
    }

    private void writeThrough(UUID uuid) {
        if (hexApi == null || hexApi.statsCache() == null) return;
        // Keep HexEconomy source- and binary-compatible with the existing HexCore API.
        // refreshCoins(UUID) already reloads the authoritative XConomy balance into
        // HexCore's cache, so no newer HexCore method is required here.
        try {
            hexApi.statsCache().refreshCoins(uuid);
        } catch (RuntimeException ignored) {
            // The XConomy transaction has already succeeded. Cache refresh failure must
            // not turn a successful payment into a failed transaction.
        }
    }

    private BigDecimal balanceAsBigDecimal(UUID uuid) {
        if (uuid == null || !isAvailable()) return BigDecimal.ZERO;
        try { return BigDecimal.valueOf(backend.getBalance(uuid).orElse(0)); }
        catch (RuntimeException ignored) { return BigDecimal.ZERO; }
    }

    private static Integer positiveInt(BigDecimal amount) {
        Integer value = exactIntOrNull(amount);
        return value != null && value > 0 ? value : null;
    }

    private static Integer nonNegativeInt(BigDecimal amount) {
        Integer value = exactIntOrNull(amount);
        return value != null && value >= 0 ? value : null;
    }

    private static Integer exactIntOrNull(BigDecimal amount) {
        if (amount == null) return null;
        try { return amount.intValueExact(); }
        catch (ArithmeticException ex) { return null; }
    }

    private static int requireWholeInt(BigDecimal amount) {
        try { return amount.intValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("HEX_COINS amount must be a whole int: " + amount.toPlainString(), ex); }
    }

    private static void requireUuid(UUID uuid) {
        if (uuid == null) throw new IllegalArgumentException("playerUuid cannot be null");
    }
}
