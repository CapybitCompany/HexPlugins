package hex.economy.currency;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** Integer-only adapter boundary around XConomy. */
public interface HexCoinsBackend {
    boolean isAvailable();
    OptionalInt getBalance(UUID uuid);
    Optional<CurrencyAccount> findAccountByName(String playerName);
    BackendTransactionResult deposit(UUID uuid, String playerName, int amount, String reason);
    BackendTransactionResult withdraw(UUID uuid, String playerName, int amount, String reason);
    BackendTransactionResult set(UUID uuid, String playerName, int amount, String reason);
}
