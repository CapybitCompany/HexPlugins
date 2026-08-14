package hex.auctionbazaar.auction.command;

import hex.auctionbazaar.HexAuctionBazaarPlugin;
import hex.auctionbazaar.auction.gui.AuctionBrowseGui;
import hex.auctionbazaar.auction.gui.AuctionClaimsGui;
import hex.auctionbazaar.auction.gui.AuctionMyListingsGui;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.config.AuctionConfig;
import hex.auctionbazaar.util.MessageFactory;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static hex.auctionbazaar.util.MessageFactory.placeholders;

public final class AuctionCommand implements CommandExecutor, TabCompleter {

    private final HexAuctionBazaarPlugin plugin;

    public AuctionCommand(HexAuctionBazaarPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        MessageFactory messages = plugin.messages();
        AuctionConfig cfg = plugin.config().auction();
        // admin (dbstatus) i reload muszą działać nawet przy auction.enabled:false
        // (diagnostyka /admin dbstatus oraz naprawa konfiguracji).
        String sub0 = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        // Jednolita bramka „enabled" (punkt #4): globalny tryb konserwacji (enabled:false) ORAZ
        // feature-specific disable (auction.enabled:false) blokują NOWĄ komercję, ale przepuszczają
        // akcje ODZYSKU/diagnostyki (reload, admin, anulowanie, odbiory, moje aukcje) - te i tak są
        // dalej chronione permisją oraz bramką DB niżej, więc środki/przedmioty gracza nie utkną.
        switch (commandGate(sub0, plugin.config().enabled(), cfg.enabled())) {
            case MAINTENANCE -> {
                messages.send(sender, "common.maintenance");
                return true;
            }
            case FEATURE_DISABLED -> {
                messages.send(sender, "common.feature-disabled");
                return true;
            }
            case ALLOW -> { /* przechodzimy dalej (permisja + DB gate) */ }
        }
        if (!sub0.equals("admin") && !sub0.equals("reload")) {
            if (!plugin.dbHealthy()) {
                messages.send(sender, "common.database-unavailable");
                return true;
            }
            if (!plugin.schemaReady()) {
                messages.send(sender, "common.schema-not-ready");
                return true;
            }
        }
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                messages.send(sender, "common.must-be-player");
                return true;
            }
            if (!sender.hasPermission(cfg.permOpen())) {
                messages.send(sender, "common.no-permission");
                return true;
            }
            AuctionBrowseGui.open(plugin, p, () -> plugin.config().auction(),
                    plugin.auctionService(), plugin.economy(), messages);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "reload" -> handleReload(sender);
            case "sell" -> handleSell(sender, args);
            case "mylistings", "mine" -> handleMyListings(sender);
            case "cancel" -> handleCancel(sender, args);
            case "claims" -> handleClaims(sender);
            case "admin" -> handleAdmin(sender, args);
            default -> {
                messages.send(sender, "common.no-permission");
                yield true;
            }
        };
    }

    /**
     * Podkomendy dozwolone w globalnym trybie konserwacji (enabled:false). Diagnostyka (reload/admin)
     * oraz bezpieczne akcje ODZYSKU (anulowanie własnej aukcji, odbiory, moje aukcje) - żeby nie uwięzić
     * przedmiotów/środków gracza. Komercyjne wejścia (przeglądanie prowadzące do kupna, sell) - zablokowane.
     */
    static boolean maintenanceAllowed(String sub0) {
        return switch (sub0) {
            case "reload", "admin", "cancel", "claims", "mylistings", "mine" -> true;
            default -> false;
        };
    }

    /** Wynik jednolitej bramki „enabled" dla podkomendy (punkt #4). */
    enum CommandGate { ALLOW, MAINTENANCE, FEATURE_DISABLED }

    /**
     * Czysta, testowalna bramka „enabled" (punkt #4): akcje ODZYSKU/diagnostyki ({@link #maintenanceAllowed})
     * są ZAWSZE przepuszczane (dalej chronione permisją i bramką DB), także przy feature-specific disable.
     * Dla komercyjnych wejść globalny tryb konserwacji ma pierwszeństwo (MAINTENANCE) przed wyłączoną
     * funkcją (FEATURE_DISABLED). Ujednolica semantykę globalnego i feature-specific disable.
     */
    static CommandGate commandGate(String sub0, boolean globalEnabled, boolean featureEnabled) {
        if (maintenanceAllowed(sub0)) {
            return CommandGate.ALLOW;
        }
        if (!globalEnabled) {
            return CommandGate.MAINTENANCE;
        }
        if (!featureEnabled) {
            return CommandGate.FEATURE_DISABLED;
        }
        return CommandGate.ALLOW;
    }

    private boolean handleReload(CommandSender sender) {
        AuctionConfig cfg = plugin.config().auction();
        if (!sender.hasPermission(cfg.permAdmin())) {
            plugin.messages().send(sender, "common.no-permission");
            return true;
        }
        plugin.reloadAllConfigs();
        plugin.messages().send(sender, "common.reloaded");
        return true;
    }

    private boolean handleSell(CommandSender sender, String[] args) {
        AuctionConfig cfg = plugin.config().auction();
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "common.must-be-player");
            return true;
        }
        if (!p.hasPermission(cfg.permSell())) {
            plugin.messages().send(sender, "common.no-permission");
            return true;
        }
        if (args.length < 2) {
            plugin.messages().send(p, "auction.invalid-price",
                    placeholders("min", cfg.minPrice().toPlainString(), "max", cfg.maxPrice().toPlainString()));
            return true;
        }
        BigDecimal price;
        try {
            price = new BigDecimal(args[1]);
        } catch (NumberFormatException ex) {
            plugin.messages().send(p, "auction.invalid-price",
                    placeholders("min", cfg.minPrice().toPlainString(), "max", cfg.maxPrice().toPlainString()));
            return true;
        }
        plugin.auctionService().sellItemInHand(p, price).thenAccept(outcome ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    switch (outcome.result()) {
                        case OK -> plugin.messages().send(p, "auction.listing-created",
                                placeholders("id", String.valueOf(outcome.listingId()),
                                        "gross", plugin.economy().format(outcome.gross()),
                                        "tax", plugin.economy().format(outcome.tax()),
                                        "net", plugin.economy().format(outcome.net())));
                        case INVALID_PRICE -> plugin.messages().send(p, "auction.invalid-price",
                                placeholders("min", cfg.minPrice().toPlainString(),
                                        "max", cfg.maxPrice().toPlainString()));
                        case NO_ITEM -> plugin.messages().send(p, "auction.item-not-in-hand");
                        case TOO_MANY -> plugin.messages().send(p, "auction.too-many-listings",
                                placeholders("max", String.valueOf(outcome.limit())));
                        case NOT_ENOUGH_MONEY -> plugin.messages().send(p, "auction.not-enough-money-for-listing",
                                placeholders("required", plugin.economy().format(outcome.required()),
                                        "fee", plugin.economy().format(outcome.listingFee()),
                                        "tax", plugin.economy().format(outcome.tax())));
                        case ECONOMY_UNAVAILABLE -> plugin.messages().send(p, "common.economy-missing");
                        case ECONOMY_ERROR -> plugin.messages().send(p, "auction.economy-error");
                        case BUSY -> plugin.messages().send(p, "auction.sell-busy");
                        case FEATURE_DISABLED -> plugin.messages().send(p, "common.feature-disabled");
                        case NO_PERMISSION -> plugin.messages().send(p, "common.no-permission");
                        case ITEM_NOT_ALLOWED -> plugin.messages().send(p, "auction.item-not-allowed");
                        case COMPENSATION_FAILED -> plugin.messages().send(p, "auction.compensation-failed");
                        // TAX_CHANGED nie wystąpi z komendy (brak wiązania procentu).
                        case TAX_CHANGED -> plugin.messages().send(p, "auction.economy-error");
                        case DB_FAILED -> plugin.messages().send(p, "common.db-error");
                    }
                }));
        return true;
    }

    private boolean handleMyListings(CommandSender sender) {
        AuctionConfig cfg = plugin.config().auction();
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "common.must-be-player");
            return true;
        }
        if (!p.hasPermission(cfg.permOpen())) {
            plugin.messages().send(sender, "common.no-permission");
            return true;
        }
        AuctionMyListingsGui.open(plugin, p, () -> plugin.config().auction(),
                plugin.auctionService(), plugin.economy(), plugin.messages());
        return true;
    }

    private boolean handleCancel(CommandSender sender, String[] args) {
        AuctionConfig cfg = plugin.config().auction();
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "common.must-be-player");
            return true;
        }
        if (!p.hasPermission(cfg.permCancelOwn())) {
            plugin.messages().send(sender, "common.no-permission");
            return true;
        }
        if (args.length < 2) {
            plugin.messages().send(p, "auction.listing-not-found",
                    placeholders("id", "?"));
            return true;
        }
        long id;
        try {
            id = Long.parseLong(args[1]);
        } catch (NumberFormatException ex) {
            plugin.messages().send(p, "auction.listing-not-found",
                    placeholders("id", args[1]));
            return true;
        }
        plugin.auctionService().cancel(p, id).thenAccept(outcome ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    switch (outcome) {
                        case OK -> plugin.messages().send(p, "auction.listing-cancelled",
                                placeholders("id", String.valueOf(id)));
                        case NOT_OWNER -> plugin.messages().send(p, "auction.listing-not-yours");
                        case NOT_ACTIVE -> plugin.messages().send(p, "auction.listing-not-active");
                        case NO_PERMISSION -> plugin.messages().send(p, "common.no-permission");
                        case NOT_FOUND -> plugin.messages().send(p, "auction.listing-not-found",
                                placeholders("id", String.valueOf(id)));
                    }
                }));
        return true;
    }

    private boolean handleClaims(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "common.must-be-player");
            return true;
        }
        if (!p.hasPermission(plugin.config().auction().permOpen())) {
            plugin.messages().send(sender, "common.no-permission");
            return true;
        }
        AuctionClaimsGui.open(plugin, p, () -> plugin.config().auction(),
                plugin.auctionService(), plugin.economy(), plugin.messages());
        return true;
    }

    /** Wynik bramki DB/schema dla podkomend admina (punkt #10). */
    enum AdminDbGate { ALLOW, DB_DOWN, SCHEMA_NOT_READY }

    /**
     * Punkt #10: precyzyjna bramka DB dla {@code /hexauction admin}. Tylko diagnostyka
     * ({@code dbstatus}; {@code reload} jest obsłużony osobno, poza handleAdmin) omija bramkę.
     * {@code cleanup} i {@code audit} MUSZĄ mieć zdrową bazę i gotowy schemat - inaczej nie
     * wykonujemy żadnej query/mutacji, tylko zwracamy poprawny polski komunikat (nigdy
     * mylącego „0 wyników").
     */
    static AdminDbGate adminDbGate(String sub, boolean dbHealthy, boolean schemaReady) {
        if ("dbstatus".equals(sub)) {
            return AdminDbGate.ALLOW;
        }
        if (!dbHealthy) {
            return AdminDbGate.DB_DOWN;
        }
        if (!schemaReady) {
            return AdminDbGate.SCHEMA_NOT_READY;
        }
        return AdminDbGate.ALLOW;
    }

    /** Egzekwuje {@link #adminDbGate}: przy DB down/schema-not-ready wysyła komunikat i zwraca false. */
    private boolean adminDbReady(CommandSender sender, String sub) {
        return switch (adminDbGate(sub, plugin.dbHealthy(), plugin.schemaReady())) {
            case DB_DOWN -> {
                plugin.messages().send(sender, "common.database-unavailable");
                yield false;
            }
            case SCHEMA_NOT_READY -> {
                plugin.messages().send(sender, "common.schema-not-ready");
                yield false;
            }
            case ALLOW -> true;
        };
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        AuctionConfig cfg = plugin.config().auction();
        if (!hasAdmin(sender, cfg)) {
            plugin.messages().send(sender, "common.no-permission");
            return true;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "auction.admin-usage");
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("cleanup")) {
            // cleanup mutuje bazę (wygasza aukcje) - wymaga zdrowej bazy i gotowego schematu.
            if (!adminDbReady(sender, sub)) {
                return true;
            }
            plugin.auctionService().expireDueListings(1000).thenAccept(count ->
                    Bukkit.getScheduler().runTask(plugin, () ->
                            plugin.messages().send(sender, "auction.cleanup-done",
                                    placeholders("count", String.valueOf(count)))));
            return true;
        }
        if (sub.equals("audit")) {
            if (!sender.hasPermission(plugin.config().auction().permAdminAudit()) && !sender.isOp()) {
                plugin.messages().send(sender, "common.no-permission");
                return true;
            }
            // audit odpytuje bazę - przy DB down/schema not ready NIE zapytujemy (bez mylącego „0 wyników").
            if (!adminDbReady(sender, sub)) {
                return true;
            }
            if (args.length < 4) {
                plugin.messages().send(sender, "auction.admin-audit-usage");
                return true;
            }
            String kind = args[2].toLowerCase(Locale.ROOT);
            String value = args[3];
            plugin.auditService().queryFormatted(kind, value, 20).thenAccept(lines ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (lines.isEmpty()) {
                            plugin.messages().send(sender, "auction.admin-audit-empty");
                        } else {
                            for (String line : lines) {
                                sender.sendMessage(hex.auctionbazaar.util.LegacyFormat.component(line));
                            }
                        }
                    }));
            return true;
        }
        if (sub.equals("dbstatus")) {
            if (!sender.hasPermission("hexauction.admin.dbstatus") && !sender.isOp()) {
                plugin.messages().send(sender, "common.no-permission");
                return true;
            }
            handleDbStatus(sender);
            return true;
        }
        plugin.messages().send(sender, "auction.admin-usage");
        return true;
    }

    /**
     * Stan połączenia z bazą (wyłącznie po polsku, bez host/user/hasła).
     * Wykonuje świeży SELECT 1 przez HexCore, pokazuje provider, wymaganie i prefiks.
     */
    private void handleDbStatus(CommandSender sender) {
        boolean required = plugin.config().database().required();
        // Prefiks czytany chronionym dostępem (może rzucać przy NoopDatabaseService).
        String prefixDisplay = resolvePrefixDisplay();
        // Diagnostyczny SELECT 1 - NIE zmienia dbHealthy/schemaReady, tylko pokazuje stan.
        java.util.concurrent.CompletableFuture<Boolean> check;
        try {
            check = plugin.hexCore().async(() -> {
                try {
                    plugin.hexCore().rawDb().queryOne("SELECT 1 AS ok", rs -> rs.getInt("ok"));
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            }).exceptionally(ex -> false);
        } catch (Throwable t) {
            check = java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        check.whenComplete((ok, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            boolean healthy = err == null && Boolean.TRUE.equals(ok);
            String state = plugin.messages().raw(healthy
                    ? "auction.dbstatus-state-ok" : "auction.dbstatus-state-down", null);
            plugin.messages().send(sender, "auction.dbstatus-header");
            plugin.messages().send(sender, "auction.dbstatus-provider");
            plugin.messages().send(sender, "auction.dbstatus-required",
                    placeholders("required", plugin.messages().raw(
                            required ? "common.yes-label" : "common.no-label", null)));
            plugin.messages().send(sender, "auction.dbstatus-healthy",
                    placeholders("state", state));
            plugin.messages().send(sender, "auction.dbstatus-prefix",
                    placeholders("prefix", prefixDisplay));
        }));
    }

    /** (niedostępny) gdy prefiks nie da się odczytać, (brak) gdy pusty, inaczej wartość. */
    private String resolvePrefixDisplay() {
        java.util.Optional<String> prefix = plugin.safeTablePrefix();
        if (prefix.isEmpty()) {
            return plugin.messages().raw("auction.dbstatus-prefix-unreadable", null);
        }
        if (prefix.get().isEmpty()) {
            return plugin.messages().raw("auction.dbstatus-prefix-empty", null);
        }
        return prefix.get();
    }

    /**
     * Admin uprawnienia: dokladnie skonfigurowana permisja LUB status OP.
     * Powod: OP-y powinny miec zawsze dostep do panelu admina, nawet gdy
     * uprawnienie nie jest im wyraznie nadane (kompatybilnosc z ekipa
     * serwera zarzadzana na poziomie OP-list).
     */
    private boolean hasAdmin(CommandSender sender, AuctionConfig cfg) {
        return sender.hasPermission(cfg.permAdmin()) || sender.isOp();
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("sell", "mylistings", "cancel", "claims", "reload", "admin"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return filter(List.of("cleanup", "audit", "dbstatus"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("audit")) {
            return filter(List.of("player", "item", "order", "listing", "market"), args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> input, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : input) {
            if (s.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(s);
            }
        }
        return out;
    }
}
