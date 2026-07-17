package hex.economy.placeholder;

import hex.economy.model.EconomyTopEntry;
import hex.economy.service.EconomyService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class EconomyPlaceholderExpansion extends PlaceholderExpansion {
    private static final String TOP_PREFIX = "top_money_";
    private static final long BALANCE_CACHE_TTL_MILLIS = 1_000L;

    private final Plugin plugin;
    private final EconomyService economy;
    private final ConcurrentMap<UUID, CachedBalance> balanceCache = new ConcurrentHashMap<>();

    public EconomyPlaceholderExpansion(Plugin plugin, EconomyService economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "hexeconomy";
    }

    @Override
    public @NotNull String getAuthor() {
        String authors = String.join(", ", plugin.getDescription().getAuthors());
        return authors.isBlank() ? "HexTeam" : authors;
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String identifier) {
        return resolve(player == null ? null : player.getUniqueId(), identifier);
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String identifier) {
        return resolve(player == null ? null : player.getUniqueId(), identifier);
    }

    private @Nullable String resolve(@Nullable UUID playerUuid, @NotNull String identifier) {
        String key = normalize(identifier);
        try {
            return switch (key) {
                case "balance", "amount" -> playerUuid == null ? "-" : plain(balance(playerUuid));
                case "balance_formatted", "formatted" -> playerUuid == null ? "-" : economy.format(balance(playerUuid));
                case "currency", "currency_name" -> economy.currencyName();
                default -> resolveTop(key);
            };
        } catch (RuntimeException exception) {
            return "-";
        }
    }

    private @Nullable String resolveTop(String key) {
        if (!key.startsWith(TOP_PREFIX)) {
            return null;
        }

        String[] parts = key.substring(TOP_PREFIX.length()).split("_", 2);
        if (parts.length != 2) {
            return null;
        }

        int position;
        try {
            position = Integer.parseInt(parts[0]);
        } catch (NumberFormatException exception) {
            return null;
        }
        if (position < 1 || position > 5) {
            return "-";
        }

        EconomyTopEntry entry = economy.getTopBalance(position);
        return switch (parts[1]) {
            case "name" -> name(entry.playerName());
            case "amount", "balance" -> entry.balance() == null ? "-" : plain(entry.balance());
            case "formatted" -> entry.balance() == null ? "-" : economy.format(entry.balance());
            default -> null;
        };
    }

    private String normalize(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return "";
        }
        String normalized = identifier.startsWith(":") ? identifier.substring(1) : identifier;
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String name(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String plain(BigDecimal value) {
        return value == null ? "-" : economy.config().normalize(value).toPlainString();
    }

    private BigDecimal balance(UUID playerUuid) {
        long now = System.currentTimeMillis();
        CachedBalance cached = balanceCache.get(playerUuid);
        if (cached != null && now < cached.expiresAtMillis()) {
            return cached.balance();
        }
        BigDecimal loaded = economy.getBalance(playerUuid);
        balanceCache.put(playerUuid, new CachedBalance(loaded, now + BALANCE_CACHE_TTL_MILLIS));
        return loaded;
    }

    private record CachedBalance(BigDecimal balance, long expiresAtMillis) {
    }
}
