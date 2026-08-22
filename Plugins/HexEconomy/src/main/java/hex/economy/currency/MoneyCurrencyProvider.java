package hex.economy.currency;

import hex.economy.api.CurrencyType;
import hex.economy.api.EconomyResult;
import hex.economy.config.EconomyConfig;
import hex.economy.database.EconomyRepository;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** MONEY provider backed by the existing smp_economy table. */
public final class MoneyCurrencyProvider implements CurrencyProvider {
    private final EconomyRepository repository;
    private volatile EconomyConfig config;

    public MoneyCurrencyProvider(EconomyRepository repository, EconomyConfig config) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void reload(EconomyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public EconomyConfig config() { return config; }

    @Override public CurrencyType type() { return CurrencyType.MONEY; }
    @Override public boolean isAvailable() { return true; }

    @Override
    public BigDecimal getBalance(UUID uuid) {
        requireUuid(uuid);
        return config.normalize(repository.getBalance(uuid).orElse(config.defaultBalance()));
    }

    public BigDecimal getOrCreateBalance(UUID uuid, String playerName) {
        requireUuid(uuid);
        return config.normalize(repository.getOrCreateBalance(uuid, playerName, config.defaultBalance()));
    }

    @Override
    public EconomyResult deposit(UUID uuid, String playerName, BigDecimal amount, String reason) {
        requireUuid(uuid);
        BigDecimal delta = positive(amount);
        if (delta == null) return EconomyResult.fail(getBalance(uuid), "INVALID_AMOUNT");
        BigDecimal balance = config.normalize(repository.add(uuid, playerName, delta, config.defaultBalance()));
        return EconomyResult.ok(balance);
    }

    @Override
    public EconomyResult withdraw(UUID uuid, String playerName, BigDecimal amount, String reason) {
        requireUuid(uuid);
        BigDecimal delta = positive(amount);
        if (delta == null) return EconomyResult.fail(getBalance(uuid), "INVALID_AMOUNT");

        EconomyRepository.WithdrawResult result = repository.withdrawIfSufficient(
                uuid, playerName, delta, config.defaultBalance(), config.allowNegativeBalance());
        BigDecimal balance = config.normalize(result.balance());
        return result.success() ? EconomyResult.ok(balance) : EconomyResult.fail(balance, "NOT_ENOUGH_FUNDS");
    }

    @Override
    public EconomyResult setBalance(UUID uuid, String playerName, BigDecimal amount, String reason) {
        requireUuid(uuid);
        if (amount == null) return EconomyResult.fail(getBalance(uuid), "INVALID_AMOUNT");
        BigDecimal normalized = config.normalize(amount);
        if (!config.allowNegativeBalance() && normalized.compareTo(BigDecimal.ZERO) < 0) {
            return EconomyResult.fail(getBalance(uuid), "NEGATIVE_DISABLED");
        }
        return EconomyResult.ok(config.normalize(repository.set(uuid, playerName, normalized)));
    }

    @Override
    public boolean has(UUID uuid, BigDecimal amount) {
        BigDecimal normalized = positive(amount);
        return normalized != null && getBalance(uuid).compareTo(normalized) >= 0;
    }

    @Override public String format(BigDecimal amount) { return config.formatAmount(amount); }
    @Override public String displayName() { return config.currencyName(); }

    private BigDecimal positive(BigDecimal amount) {
        if (amount == null) return null;
        BigDecimal normalized = config.normalize(amount);
        return normalized.compareTo(BigDecimal.ZERO) > 0 ? normalized : null;
    }

    private static void requireUuid(UUID uuid) {
        if (uuid == null) throw new IllegalArgumentException("playerUuid cannot be null");
    }
}
