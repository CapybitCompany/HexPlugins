package hex.economy.xconomy;

import hex.economy.currency.BackendTransactionResult;
import hex.economy.currency.CurrencyAccount;
import hex.economy.currency.HexCoinsBackend;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Runtime adapter for XConomy 2.25.x.
 * HEX_COINS are an integer-only currency. XConomy itself accepts BigDecimal,
 * therefore this adapter converts int -> BigDecimal only at the library boundary
 * and rejects fractional balances returned by XConomy instead of truncating them.
 */
public final class XConomyReflectionBackend implements HexCoinsBackend {
    private final Object api;
    private final Method getPlayerDataUuid;
    private final Method getPlayerDataName;
    private final Method createPlayerData;
    private final Method changePlayerBalance;

    private XConomyReflectionBackend(Object api, Class<?> apiClass) throws ReflectiveOperationException {
        this.api = api;
        this.getPlayerDataUuid = apiClass.getMethod("getPlayerData", UUID.class);
        this.getPlayerDataName = apiClass.getMethod("getPlayerData", String.class);
        this.createPlayerData = apiClass.getMethod("createPlayerData", UUID.class, String.class);
        this.changePlayerBalance = apiClass.getMethod("changePlayerBalance", UUID.class, String.class, BigDecimal.class, Boolean.class, String.class);
    }

    public static XConomyReflectionBackend create(ClassLoader xconomyClassLoader) throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName("me.yic.xconomy.api.XConomyAPI", true, xconomyClassLoader);
        Constructor<?> constructor = apiClass.getConstructor();
        return new XConomyReflectionBackend(constructor.newInstance(), apiClass);
    }

    @Override public boolean isAvailable() { return true; }

    @Override
    public OptionalInt getBalance(UUID uuid) {
        if (uuid == null) return OptionalInt.empty();
        try {
            Object data = getPlayerDataUuid.invoke(api, uuid);
            return balanceOf(data);
        } catch (ReflectiveOperationException ex) {
            throw providerException("Failed to read XConomy balance for " + uuid, ex);
        }
    }

    @Override
    public Optional<CurrencyAccount> findAccountByName(String playerName) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        try {
            Object data = getPlayerDataName.invoke(api, playerName.trim());
            if (data == null) return Optional.empty();
            UUID uuid = (UUID) data.getClass().getMethod("getUniqueId").invoke(data);
            String name = (String) data.getClass().getMethod("getName").invoke(data);
            return uuid == null ? Optional.empty() : Optional.of(new CurrencyAccount(uuid, name == null ? playerName : name));
        } catch (ReflectiveOperationException ex) {
            throw providerException("Failed to resolve XConomy player " + playerName, ex);
        }
    }

    @Override public BackendTransactionResult deposit(UUID uuid, String playerName, int amount, String reason) { return change(uuid, playerName, amount, Boolean.TRUE); }
    @Override public BackendTransactionResult withdraw(UUID uuid, String playerName, int amount, String reason) { return change(uuid, playerName, amount, Boolean.FALSE); }
    @Override public BackendTransactionResult set(UUID uuid, String playerName, int amount, String reason) { return change(uuid, playerName, amount, null); }

    private BackendTransactionResult change(UUID uuid, String playerName, int amount, Boolean isAdd) {
        if (uuid == null || amount < 0 || (isAdd != null && amount == 0)) return BackendTransactionResult.fail(safeBalance(uuid), "INVALID_AMOUNT");
        try {
            Object data = getPlayerDataUuid.invoke(api, uuid);
            if (data == null) {
                if (playerName == null || playerName.isBlank()) return BackendTransactionResult.fail(0, "PLAYER_NOT_FOUND");
                createPlayerData.invoke(api, uuid, playerName);
                data = getPlayerDataUuid.invoke(api, uuid);
                if (data == null) return BackendTransactionResult.fail(0, "PLAYER_NOT_FOUND");
            }

            // Refuse to mutate an already-invalid fractional HEX_COINS account.
            balanceOf(data);

            BigDecimal xconomyAmount = BigDecimal.valueOf(amount);
            int code = ((Number) changePlayerBalance.invoke(api, uuid, playerName, xconomyAmount, isAdd, "HexEconomy")).intValue();
            int balance = getBalance(uuid).orElse(0);
            return switch (code) {
                case 0 -> BackendTransactionResult.ok(balance);
                case 1 -> BackendTransactionResult.fail(balance, "PROVIDER_ERROR");
                case 2 -> BackendTransactionResult.fail(balance, "NOT_ENOUGH_FUNDS");
                case 3 -> BackendTransactionResult.fail(balance, "BALANCE_LIMIT");
                default -> BackendTransactionResult.fail(balance, "PROVIDER_ERROR");
            };
        } catch (ReflectiveOperationException | IllegalStateException ex) {
            return BackendTransactionResult.fail(safeBalance(uuid), "PROVIDER_ERROR");
        }
    }

    private OptionalInt balanceOf(Object data) throws ReflectiveOperationException {
        if (data == null) return OptionalInt.empty();
        Object value = data.getClass().getMethod("getBalance").invoke(data);
        if (value == null) return OptionalInt.empty();
        BigDecimal amount = value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
        return OptionalInt.of(toIntExact(amount));
    }

    private int safeBalance(UUID uuid) {
        try { return getBalance(uuid).orElse(0); } catch (RuntimeException ignored) { return 0; }
    }

    private static int toIntExact(BigDecimal amount) {
        try {
            return amount.intValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("HEX_COINS balance must be a whole int, got: " + amount.toPlainString(), ex);
        }
    }

    private static IllegalStateException providerException(String message, Exception cause) {
        Throwable root = cause.getCause() == null ? cause : cause.getCause();
        return new IllegalStateException(message + ": " + root.getMessage(), root);
    }
}
