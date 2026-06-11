package hex.economy.service;

import hex.economy.api.EconomyResult;
import hex.economy.api.HexEconomyApi;
import hex.economy.config.EconomyConfig;
import hex.economy.database.EconomyRepository;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public final class EconomyService implements HexEconomyApi {
    private final EconomyRepository repository;
    private volatile EconomyConfig config;

    public EconomyService(EconomyRepository repository, EconomyConfig config) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void reload(EconomyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public EconomyConfig config() {
        return config;
    }

    @Override
    public BigDecimal getBalance(UUID playerUuid) {
        requireUuid(playerUuid);
        return config.normalize(repository.getBalance(playerUuid).orElse(config.defaultBalance()));
    }

    public BigDecimal getOrCreateBalance(UUID playerUuid, String playerName) {
        requireUuid(playerUuid);
        return config.normalize(repository.getOrCreateBalance(playerUuid, playerName, config.defaultBalance()));
    }

    @Override
    public EconomyResult deposit(UUID playerUuid, String playerName, BigDecimal amount, String reason) {
        requireUuid(playerUuid);
        BigDecimal delta = positive(amount);
        if (delta == null) {
            return EconomyResult.fail(getBalance(playerUuid), "INVALID_AMOUNT");
        }
        BigDecimal balance = config.normalize(repository.add(playerUuid, playerName, delta, config.defaultBalance()));
        return EconomyResult.ok(balance);
    }

    @Override
    public EconomyResult withdraw(UUID playerUuid, String playerName, BigDecimal amount, String reason) {
        requireUuid(playerUuid);
        BigDecimal delta = positive(amount);
        if (delta == null) {
            return EconomyResult.fail(getBalance(playerUuid), "INVALID_AMOUNT");
        }
        BigDecimal current = getOrCreateBalance(playerUuid, playerName);
        if (!config.allowNegativeBalance() && current.compareTo(delta) < 0) {
            return EconomyResult.fail(current, "NOT_ENOUGH_FUNDS");
        }
        BigDecimal balance = config.normalize(repository.add(playerUuid, playerName, delta.negate(), config.defaultBalance()));
        return EconomyResult.ok(balance);
    }

    @Override
    public EconomyResult setBalance(UUID playerUuid, String playerName, BigDecimal amount, String reason) {
        requireUuid(playerUuid);
        if (amount == null) {
            return EconomyResult.fail(getBalance(playerUuid), "INVALID_AMOUNT");
        }
        BigDecimal normalized = config.normalize(amount);
        if (!config.allowNegativeBalance() && normalized.compareTo(BigDecimal.ZERO) < 0) {
            return EconomyResult.fail(getBalance(playerUuid), "NEGATIVE_DISABLED");
        }
        return EconomyResult.ok(config.normalize(repository.set(playerUuid, playerName, normalized)));
    }

    @Override
    public boolean has(UUID playerUuid, BigDecimal amount) {
        BigDecimal normalized = positive(amount);
        return normalized != null && getBalance(playerUuid).compareTo(normalized) >= 0;
    }

    @Override
    public String format(BigDecimal amount) {
        return config.formatAmount(amount);
    }

    @Override
    public String currencyName() {
        return config.currencyName();
    }

    private BigDecimal positive(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        BigDecimal normalized = config.normalize(amount);
        if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return normalized;
    }

    private void requireUuid(UUID playerUuid) {
        if (playerUuid == null) {
            throw new IllegalArgumentException("playerUuid cannot be null");
        }
    }
}
