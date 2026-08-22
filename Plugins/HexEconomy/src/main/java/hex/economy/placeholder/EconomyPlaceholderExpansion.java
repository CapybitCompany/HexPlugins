package hex.economy.placeholder;

import hex.core.api.HexApi;
import hex.economy.HexEconomyPlugin;
import hex.economy.database.EconomyRepository;
import hex.economy.service.EconomyService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Internal PlaceholderAPI expansion for the MONEY currency handled by HexEconomy.
 *
 * <p>All database access is performed asynchronously. Placeholder requests only read
 * cached values and may enqueue an async refresh when a player's balance is missing.</p>
 */
public final class EconomyPlaceholderExpansion extends PlaceholderExpansion {
    private static final int TOP_LIMIT = 5;

    private final HexEconomyPlugin plugin;
    private final HexApi hexApi;
    private final EconomyService service;
    private final EconomyRepository repository;

    private final AtomicReference<List<EconomyRepository.TopBalance>> topBalances =
            new AtomicReference<>(List.of());
    private final ConcurrentHashMap<UUID, BigDecimal> playerBalances = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);
    private final Set<UUID> pendingBalanceRefreshes = ConcurrentHashMap.newKeySet();

    private BukkitTask refreshTask;

    public EconomyPlaceholderExpansion(
            HexEconomyPlugin plugin,
            HexApi hexApi,
            EconomyService service,
            EconomyRepository repository
    ) {
        this.plugin = plugin;
        this.hexApi = hexApi;
        this.service = service;
        this.repository = repository;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "hexeconomy";
    }

    @Override
    public @NotNull String getAuthor() {
        List<String> authors = plugin.getDescription().getAuthors();
        return authors.isEmpty() ? "Hex Network" : String.join(", ", authors);
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
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String parameters) {
        String key = parameters.toLowerCase(Locale.ROOT);

        if (key.equals("balance") || key.equals("money")) {
            return playerBalance(player, true);
        }
        if (key.equals("balance_raw") || key.equals("money_raw")) {
            return playerBalance(player, false);
        }

        TopRequest topRequest = parseTopRequest(key);
        if (topRequest == null) {
            return null;
        }
        return topValue(topRequest);
    }

    public void startRefreshing() {
        stopRefreshing();
        long refreshTicks = Math.max(
                20L,
                plugin.getConfig().getLong("placeholders.refresh-seconds", 10L) * 20L
        );
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAsync, 0L, refreshTicks);
    }

    public void stopRefreshing() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    public void refreshNow() {
        refreshAsync();
    }

    private String playerBalance(OfflinePlayer player, boolean formatted) {
        if (player == null) {
            return "";
        }

        UUID uuid = player.getUniqueId();
        BigDecimal balance = playerBalances.get(uuid);
        if (balance == null) {
            requestPlayerRefresh(uuid, player.getName());
            return plugin.getConfig().getString(
                    formatted ? "placeholders.loading-balance" : "placeholders.loading-balance-raw",
                    formatted
                            ? service.format(service.config().defaultBalance())
                            : service.config().normalize(service.config().defaultBalance()).toPlainString()
            );
        }

        BigDecimal normalized = service.config().normalize(balance);
        return formatted ? service.format(normalized) : normalized.toPlainString();
    }

    private String topValue(TopRequest request) {
        List<EconomyRepository.TopBalance> current = topBalances.get();
        if (request.position() < 1 || request.position() > TOP_LIMIT || request.position() > current.size()) {
            return switch (request.field()) {
                case NAME -> plugin.getConfig().getString("placeholders.empty-player", "-");
                case BALANCE -> plugin.getConfig().getString(
                        "placeholders.empty-balance",
                        service.format(BigDecimal.ZERO)
                );
                case BALANCE_RAW -> plugin.getConfig().getString(
                        "placeholders.empty-balance-raw",
                        service.config().normalize(BigDecimal.ZERO).toPlainString()
                );
                case COMBINED -> plugin.getConfig().getString("placeholders.empty-entry", "-");
            };
        }

        EconomyRepository.TopBalance entry = current.get(request.position() - 1);
        BigDecimal balance = service.config().normalize(entry.balance());
        return switch (request.field()) {
            case NAME -> entry.playerName();
            case BALANCE -> service.format(balance);
            case BALANCE_RAW -> balance.toPlainString();
            case COMBINED -> plugin.getConfig().getString(
                            "placeholders.top-format",
                            "{position}. {player} - {balance}"
                    )
                    .replace("{position}", Integer.toString(request.position()))
                    .replace("{player}", entry.playerName())
                    .replace("{balance}", service.format(balance))
                    .replace("{balance_raw}", balance.toPlainString());
        };
    }

    private void refreshAsync() {
        if (!refreshRunning.compareAndSet(false, true)) {
            return;
        }

        // Snapshot Bukkit player objects on the main thread before entering DB async work.
        List<PlayerSnapshot> onlinePlayers = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlinePlayers.add(new PlayerSnapshot(player.getUniqueId(), player.getName()));
        }

        hexApi.db().async(() -> {
            List<EconomyRepository.TopBalance> top = repository.getTopBalances(TOP_LIMIT);
            List<PlayerBalance> onlineBalances = new ArrayList<>(onlinePlayers.size());
            for (PlayerSnapshot player : onlinePlayers) {
                BigDecimal balance = repository.getOrCreateBalance(
                        player.uuid(),
                        player.name(),
                        service.config().defaultBalance()
                );
                onlineBalances.add(new PlayerBalance(player.uuid(), balance));
            }
            return new RefreshResult(top, onlineBalances);
        }).thenAccept(result -> {
            topBalances.set(List.copyOf(result.topBalances()));
            for (PlayerBalance playerBalance : result.onlineBalances()) {
                playerBalances.put(
                        playerBalance.uuid(),
                        service.config().normalize(playerBalance.balance())
                );
            }
        }).exceptionally(ex -> {
            plugin.getLogger().warning("HexEconomy placeholder refresh failed: " + rootMessage(ex));
            return null;
        }).whenComplete((ignored, ex) -> refreshRunning.set(false));
    }

    private void requestPlayerRefresh(UUID uuid, String playerName) {
        if (!pendingBalanceRefreshes.add(uuid)) {
            return;
        }

        hexApi.db().async(() -> repository.getOrCreateBalance(
                uuid,
                playerName,
                service.config().defaultBalance()
        )).thenAccept(balance -> playerBalances.put(uuid, service.config().normalize(balance)))
                .whenComplete((ignored, ex) -> {
                    pendingBalanceRefreshes.remove(uuid);
                    if (ex != null) {
                        plugin.getLogger().warning(
                                "HexEconomy balance placeholder refresh failed: " + rootMessage(ex)
                        );
                    }
                });
    }

    private TopRequest parseTopRequest(String key) {
        String normalized = key;
        if (normalized.startsWith("top")
                && normalized.length() > 3
                && Character.isDigit(normalized.charAt(3))) {
            normalized = "top_" + normalized.substring(3);
        }
        if (!normalized.startsWith("top_")) {
            return null;
        }

        String[] parts = normalized.substring(4).split("_");
        if (parts.length == 0) {
            return null;
        }

        int position;
        try {
            position = Integer.parseInt(parts[0]);
        } catch (NumberFormatException exception) {
            return null;
        }

        if (parts.length == 1) {
            return new TopRequest(position, TopField.COMBINED);
        }
        if (parts.length == 2 && (parts[1].equals("name") || parts[1].equals("player"))) {
            return new TopRequest(position, TopField.NAME);
        }
        if (parts.length == 2
                && (parts[1].equals("balance") || parts[1].equals("money") || parts[1].equals("amount"))) {
            return new TopRequest(position, TopField.BALANCE);
        }
        if (parts.length == 3
                && (parts[1].equals("balance") || parts[1].equals("money") || parts[1].equals("amount"))
                && parts[2].equals("raw")) {
            return new TopRequest(position, TopField.BALANCE_RAW);
        }
        return null;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private enum TopField {
        NAME,
        BALANCE,
        BALANCE_RAW,
        COMBINED
    }

    private record TopRequest(int position, TopField field) {}
    private record PlayerSnapshot(UUID uuid, String name) {}
    private record PlayerBalance(UUID uuid, BigDecimal balance) {}
    private record RefreshResult(
            List<EconomyRepository.TopBalance> topBalances,
            List<PlayerBalance> onlineBalances
    ) {}
}
