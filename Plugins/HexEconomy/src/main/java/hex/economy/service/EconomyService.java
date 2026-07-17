package hex.economy.service;

import hex.economy.api.EconomyResult;
import hex.economy.api.HexEconomyApi;
import hex.economy.config.EconomyConfig;
import hex.economy.database.EconomyRepository;
import hex.economy.model.EconomyTopEntry;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class EconomyService implements HexEconomyApi {
    private static final int TOP_LIMIT = 5;
    private static final long TOP_CACHE_TTL_MILLIS = 15_000L;

    private final EconomyRepository repository;
    private volatile EconomyConfig config;
    private volatile TopCache topCache = new TopCache(List.of(), 0L);

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
        BigDecimal balance = config.normalize(repository.getOrCreateBalance(playerUuid, playerName, config.defaultBalance()));
        invalidateTopCache();
        return balance;
    }

    @Override
    public EconomyResult deposit(UUID playerUuid, String playerName, BigDecimal amount, String reason) {
        requireUuid(playerUuid);
        BigDecimal delta = positive(amount);
        if (delta == null) {
            return EconomyResult.fail(getBalance(playerUuid), "INVALID_AMOUNT");
        }
        BigDecimal balance = config.normalize(repository.add(playerUuid, playerName, delta, config.defaultBalance()));
        invalidateTopCache();
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
        invalidateTopCache();
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
        BigDecimal balance = config.normalize(repository.set(playerUuid, playerName, normalized));
        invalidateTopCache();
        return EconomyResult.ok(balance);
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

    public EconomyTopEntry getTopBalance(int position) {
        if (position < 1 || position > TOP_LIMIT) {
            return EconomyTopEntry.empty();
        }

        TopCache current = topCache;
        long now = System.currentTimeMillis();
        if (now >= current.expiresAtMillis()) {
            current = refreshTopCache(now);
        }

        int index = position - 1;
        return index < current.entries().size()
                ? current.entries().get(index)
                : EconomyTopEntry.empty();
    }

    private synchronized TopCache refreshTopCache(long now) {
        TopCache current = topCache;
        if (now < current.expiresAtMillis()) {
            return current;
        }

        List<EconomyTopEntry> entries;
        long expiresAt = now + TOP_CACHE_TTL_MILLIS;
        try {
            List<EconomyTopEntry> loaded = repository.getTopBalances(TOP_LIMIT);
            entries = loaded == null ? List.of() : List.copyOf(loaded);
        } catch (RuntimeException exception) {
            entries = List.of();
            expiresAt = now + 1_000L;
        }
        TopCache refreshed = new TopCache(entries, expiresAt);
        topCache = refreshed;
        return refreshed;
    }

    private void invalidateTopCache() {
        topCache = new TopCache(List.of(), 0L);
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

    private record TopCache(List<EconomyTopEntry> entries, long expiresAtMillis) {
    }
}
