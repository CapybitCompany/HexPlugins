package hex.economy.currency;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** Safe backend used when XConomy is not installed or its API cannot be attached. */
public final class UnavailableHexCoinsBackend implements HexCoinsBackend {
    @Override public boolean isAvailable() { return false; }
    @Override public OptionalInt getBalance(UUID uuid) { return OptionalInt.empty(); }
    @Override public Optional<CurrencyAccount> findAccountByName(String playerName) { return Optional.empty(); }
    @Override public BackendTransactionResult deposit(UUID uuid, String playerName, int amount, String reason) { return unavailable(); }
    @Override public BackendTransactionResult withdraw(UUID uuid, String playerName, int amount, String reason) { return unavailable(); }
    @Override public BackendTransactionResult set(UUID uuid, String playerName, int amount, String reason) { return unavailable(); }
    private BackendTransactionResult unavailable() { return BackendTransactionResult.fail(0, "CURRENCY_UNAVAILABLE"); }
}
