package hex.economy.service;

import hex.economy.api.CurrencyType;
import hex.economy.api.EconomyResult;
import hex.economy.api.HexEconomyApi;
import hex.economy.config.EconomyConfig;
import hex.economy.currency.CurrencyAccount;
import hex.economy.currency.CurrencyProvider;
import hex.economy.currency.HexCoinsCurrencyProvider;
import hex.economy.currency.MoneyCurrencyProvider;
import hex.economy.database.EconomyRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Public service implementation and multi-currency facade. */
public final class EconomyService implements HexEconomyApi {
    private final EconomyRepository repository;
    private final MoneyCurrencyProvider moneyProvider;
    private final Map<CurrencyType, CurrencyProvider> providers = new EnumMap<>(CurrencyType.class);
    private volatile EconomyConfig config;

    /** Legacy constructor retained. HEX_COINS can be registered later. */
    public EconomyService(EconomyRepository repository, EconomyConfig config) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.config = Objects.requireNonNull(config, "config");
        this.moneyProvider = new MoneyCurrencyProvider(repository, config);
        this.providers.put(CurrencyType.MONEY, moneyProvider);
    }

    public void registerProvider(CurrencyProvider provider) {
        if (provider != null) providers.put(provider.type(), provider);
    }

    public void reload(EconomyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.moneyProvider.reload(config);
    }

    public void configureHexCoins(String displayName, String format) {
        CurrencyProvider provider = providers.get(CurrencyType.HEX_COINS);
        if (provider instanceof HexCoinsCurrencyProvider hexCoins) hexCoins.configure(displayName, format);
    }

    public EconomyConfig config() { return config; }

    /** Resolves an existing MONEY account without manufacturing OfflinePlayer UUIDs. */
    public Optional<EconomyRepository.PlayerAccount> findExistingAccountByName(String playerName) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        List<EconomyRepository.PlayerAccount> matches = repository.findAccountsByName(playerName.trim());
        if (matches.isEmpty()) return Optional.empty();
        if (matches.size() == 1) return Optional.of(matches.get(0));

        List<EconomyRepository.PlayerAccount> nonSynthetic = matches.stream()
                .filter(account -> !account.uuid().equals(offlineUuid(account.playerName() == null ? playerName : account.playerName())))
                .toList();
        if (nonSynthetic.size() == 1) return Optional.of(nonSynthetic.get(0));

        throw new IllegalStateException("Niejednoznaczne konto gracza '" + playerName +
                "': znaleziono " + matches.size() + " rekordy o różnych UUID. Wymagane ręczne scalenie duplikatów.");
    }

    public Optional<CurrencyAccount> findExistingAccountByName(String playerName, CurrencyType currency) {
        if (currency == CurrencyType.MONEY) {
            return findExistingAccountByName(playerName).map(a -> new CurrencyAccount(a.uuid(), a.playerName()));
        }
        CurrencyProvider provider = providers.get(currency);
        if (provider instanceof HexCoinsCurrencyProvider hexCoins) return hexCoins.findAccountByName(playerName);
        return Optional.empty();
    }

    private static UUID offlineUuid(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }

    // ===== LEGACY API — always MONEY =====
    @Override public BigDecimal getBalance(UUID playerUuid) { return moneyProvider.getBalance(playerUuid); }
    public BigDecimal getOrCreateBalance(UUID playerUuid, String playerName) { return moneyProvider.getOrCreateBalance(playerUuid, playerName); }
    @Override public EconomyResult deposit(UUID playerUuid, String playerName, BigDecimal amount, String reason) { return moneyProvider.deposit(playerUuid, playerName, amount, reason); }
    @Override public EconomyResult withdraw(UUID playerUuid, String playerName, BigDecimal amount, String reason) { return moneyProvider.withdraw(playerUuid, playerName, amount, reason); }
    @Override public EconomyResult setBalance(UUID playerUuid, String playerName, BigDecimal amount, String reason) { return moneyProvider.setBalance(playerUuid, playerName, amount, reason); }
    @Override public boolean has(UUID playerUuid, BigDecimal amount) { return moneyProvider.has(playerUuid, amount); }
    @Override public String format(BigDecimal amount) { return moneyProvider.format(amount); }
    @Override public String currencyName() { return moneyProvider.displayName(); }

    // ===== MULTI-CURRENCY API =====
    @Override
    public BigDecimal getBalance(UUID playerUuid, CurrencyType currency) {
        CurrencyProvider provider = availableProvider(currency);
        if (provider == null) throw new IllegalStateException("Currency unavailable: " + currency);
        return provider.getBalance(playerUuid);
    }

    @Override
    public boolean has(UUID playerUuid, CurrencyType currency, BigDecimal amount) {
        CurrencyProvider provider = availableProvider(currency);
        return provider != null && provider.has(playerUuid, amount);
    }

    @Override public EconomyResult deposit(UUID uuid, String name, CurrencyType currency, BigDecimal amount, String reason) { return mutate(uuid, name, currency, amount, reason, 0); }
    @Override public EconomyResult withdraw(UUID uuid, String name, CurrencyType currency, BigDecimal amount, String reason) { return mutate(uuid, name, currency, amount, reason, 1); }
    @Override public EconomyResult setBalance(UUID uuid, String name, CurrencyType currency, BigDecimal amount, String reason) { return mutate(uuid, name, currency, amount, reason, 2); }

    @Override
    public String format(CurrencyType currency, BigDecimal amount) {
        CurrencyProvider provider = providers.get(currency);
        return provider == null ? (amount == null ? "0" : amount.toPlainString()) : provider.format(amount);
    }

    @Override
    public String currencyName(CurrencyType currency) {
        CurrencyProvider provider = providers.get(currency);
        return provider == null ? (currency == null ? "unknown" : currency.name()) : provider.displayName();
    }

    @Override public boolean isCurrencyAvailable(CurrencyType currency) {
        CurrencyProvider provider = providers.get(currency);
        return provider != null && provider.isAvailable();
    }


    // ===== INTEGER-ONLY HEX_COINS API =====
    @Override
    public int getHexCoins(UUID playerUuid) {
        HexCoinsCurrencyProvider provider = hexCoinsProvider();
        if (provider == null) throw new IllegalStateException("Currency unavailable: HEX_COINS");
        return provider.getIntBalance(playerUuid);
    }

    @Override
    public boolean hasHexCoins(UUID playerUuid, int amount) {
        HexCoinsCurrencyProvider provider = hexCoinsProvider();
        return provider != null && provider.hasInt(playerUuid, amount);
    }

    @Override
    public EconomyResult depositHexCoins(UUID uuid, String name, int amount, String reason) {
        HexCoinsCurrencyProvider provider = hexCoinsProvider();
        return provider == null ? EconomyResult.fail(BigDecimal.ZERO, "CURRENCY_UNAVAILABLE") : provider.depositInt(uuid, name, amount, reason);
    }

    @Override
    public EconomyResult withdrawHexCoins(UUID uuid, String name, int amount, String reason) {
        HexCoinsCurrencyProvider provider = hexCoinsProvider();
        return provider == null ? EconomyResult.fail(BigDecimal.ZERO, "CURRENCY_UNAVAILABLE") : provider.withdrawInt(uuid, name, amount, reason);
    }

    @Override
    public EconomyResult setHexCoins(UUID uuid, String name, int amount, String reason) {
        HexCoinsCurrencyProvider provider = hexCoinsProvider();
        return provider == null ? EconomyResult.fail(BigDecimal.ZERO, "CURRENCY_UNAVAILABLE") : provider.setIntBalance(uuid, name, amount, reason);
    }

    private HexCoinsCurrencyProvider hexCoinsProvider() {
        CurrencyProvider provider = availableProvider(CurrencyType.HEX_COINS);
        return provider instanceof HexCoinsCurrencyProvider hexCoins ? hexCoins : null;
    }

    private EconomyResult mutate(UUID uuid, String name, CurrencyType currency, BigDecimal amount, String reason, int operation) {
        CurrencyProvider provider = availableProvider(currency);
        if (provider == null) return EconomyResult.fail(BigDecimal.ZERO, "CURRENCY_UNAVAILABLE");
        return switch (operation) {
            case 0 -> provider.deposit(uuid, name, amount, reason);
            case 1 -> provider.withdraw(uuid, name, amount, reason);
            case 2 -> provider.setBalance(uuid, name, amount, reason);
            default -> EconomyResult.fail(BigDecimal.ZERO, "PROVIDER_ERROR");
        };
    }

    private CurrencyProvider availableProvider(CurrencyType currency) {
        if (currency == null) return null;
        CurrencyProvider provider = providers.get(currency);
        return provider != null && provider.isAvailable() ? provider : null;
    }
}
