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
        if (!plugin.schemaReady()) {
            messages.send(sender, "common.schema-not-ready");
            return true;
        }
        AuctionConfig cfg = plugin.config().auction();
        if (!cfg.enabled()) {
            messages.send(sender, "common.feature-disabled");
            return true;
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
                                        "price", plugin.economy().format(price)));
                        case INVALID_PRICE -> plugin.messages().send(p, "auction.invalid-price",
                                placeholders("min", cfg.minPrice().toPlainString(),
                                        "max", cfg.maxPrice().toPlainString()));
                        case NO_ITEM -> plugin.messages().send(p, "auction.item-not-in-hand");
                        case TOO_MANY -> plugin.messages().send(p, "auction.too-many-listings",
                                placeholders("max", String.valueOf(cfg.maxActiveListingsPerPlayer())));
                        case ECONOMY_FAILED -> plugin.messages().send(p, "common.economy-missing");
                        case DB_FAILED -> plugin.messages().send(p, "common.schema-not-ready");
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
            plugin.auctionService().expireDueListings(1000).thenAccept(count ->
                    Bukkit.getScheduler().runTask(plugin, () ->
                            plugin.messages().send(sender, "auction.cleanup-done",
                                    placeholders("count", String.valueOf(count)))));
            return true;
        }
        if (sub.equals("audit")) {
            if (!sender.hasPermission("hexauction.admin.audit") && !sender.isOp()) {
                plugin.messages().send(sender, "common.no-permission");
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
        plugin.messages().send(sender, "auction.admin-usage");
        return true;
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
            return filter(List.of("cleanup", "audit"), args[1]);
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
