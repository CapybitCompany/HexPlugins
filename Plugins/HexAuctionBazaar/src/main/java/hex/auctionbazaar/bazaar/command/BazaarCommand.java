package hex.auctionbazaar.bazaar.command;

import hex.auctionbazaar.HexAuctionBazaarPlugin;
import hex.auctionbazaar.bazaar.gui.BazaarMainGui;
import hex.auctionbazaar.bazaar.service.BazaarService;
import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.util.MessageFactory;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static hex.auctionbazaar.util.MessageFactory.placeholders;

public final class BazaarCommand implements CommandExecutor, TabCompleter {

    private final HexAuctionBazaarPlugin plugin;

    public BazaarCommand(HexAuctionBazaarPlugin plugin) {
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
        BazaarConfig cfg = plugin.config().bazaar();
        if (!cfg.enabled()) {
            messages.send(sender, "common.feature-disabled");
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                messages.send(sender, "common.must-be-player");
                return true;
            }
            if (!p.hasPermission(cfg.permOpen())) {
                messages.send(sender, "common.no-permission");
                return true;
            }
            BazaarMainGui.open(plugin, p, () -> plugin.config().bazaar(),
                    plugin.bazaarService(), plugin.economy(), messages);
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            case "buy" -> handleBuy(sender, args);
            case "sell" -> handleSell(sender, args);
            default -> true;
        };
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission(plugin.config().bazaar().permAdmin())) {
            plugin.messages().send(sender, "common.no-permission");
            return true;
        }
        plugin.reloadAllConfigs();
        plugin.messages().send(sender, "common.reloaded");
        return true;
    }

    private boolean handleBuy(CommandSender sender, String[] args) {
        BazaarConfig cfg = plugin.config().bazaar();
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "common.must-be-player");
            return true;
        }
        if (!p.hasPermission(cfg.permBuy())) {
            plugin.messages().send(sender, "common.no-permission");
            return true;
        }
        if (args.length < 3) {
            plugin.messages().send(p, "bazaar.invalid-quantity");
            return true;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
        int qty;
        try {
            qty = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            plugin.messages().send(p, "bazaar.invalid-quantity");
            return true;
        }
        plugin.bazaarService().buy(p, key, qty).thenAccept(outcome ->
                Bukkit.getScheduler().runTask(plugin, () -> reportBuy(p, key, qty, outcome)));
        return true;
    }

    private boolean handleSell(CommandSender sender, String[] args) {
        BazaarConfig cfg = plugin.config().bazaar();
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "common.must-be-player");
            return true;
        }
        if (!p.hasPermission(cfg.permSell())) {
            plugin.messages().send(sender, "common.no-permission");
            return true;
        }
        if (args.length < 3) {
            plugin.messages().send(p, "bazaar.invalid-quantity");
            return true;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
        int qty;
        try {
            qty = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            plugin.messages().send(p, "bazaar.invalid-quantity");
            return true;
        }
        plugin.bazaarService().sell(p, key, qty).thenAccept(outcome ->
                Bukkit.getScheduler().runTask(plugin, () -> reportSell(p, key, qty, outcome)));
        return true;
    }

    private void reportBuy(Player p, String key, int qty, BazaarService.BuyOutcome outcome) {
        switch (outcome.result()) {
            case OK -> {
                plugin.messages().send(p, "bazaar.bought", placeholders(
                        "amount", String.valueOf(qty),
                        "item", key,
                        "total", plugin.economy().format(outcome.total())));
                if (outcome.wentToClaim()) {
                    plugin.messages().send(p, "bazaar.inventory-full-claim");
                }
            }
            case NOT_ENOUGH_STOCK -> plugin.messages().send(p, "bazaar.not-enough-stock",
                    placeholders("stock", "0"));
            case NOT_ENOUGH_MONEY -> plugin.messages().send(p, "bazaar.not-enough-money");
            case UNKNOWN_ITEM -> plugin.messages().send(p, "bazaar.unknown-item",
                    placeholders("key", key));
            case BUY_DISABLED -> plugin.messages().send(p, "bazaar.buy-disabled");
            case ECONOMY_UNAVAILABLE -> plugin.messages().send(p, "common.economy-missing");
            case INVALID_QTY -> plugin.messages().send(p, "bazaar.invalid-quantity");
            case DB_FAILED -> plugin.messages().send(p, "common.schema-not-ready");
        }
    }

    private void reportSell(Player p, String key, int qty, BazaarService.SellOutcome outcome) {
        switch (outcome.result()) {
            case OK -> plugin.messages().send(p, "bazaar.sold", placeholders(
                    "amount", String.valueOf(qty),
                    "item", key,
                    "total", plugin.economy().format(outcome.total())));
            case NOT_ENOUGH_ITEMS -> plugin.messages().send(p, "bazaar.not-enough-items",
                    placeholders("item", key));
            case UNKNOWN_ITEM -> plugin.messages().send(p, "bazaar.unknown-item",
                    placeholders("key", key));
            case SELL_DISABLED -> plugin.messages().send(p, "bazaar.sell-disabled");
            case ECONOMY_UNAVAILABLE -> plugin.messages().send(p, "common.economy-missing");
            case INVALID_QTY -> plugin.messages().send(p, "bazaar.invalid-quantity");
            case DB_FAILED -> plugin.messages().send(p, "common.schema-not-ready");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("buy", "sell", "reload"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("buy") || args[0].equalsIgnoreCase("sell"))) {
            return filter(new ArrayList<>(plugin.config().bazaar().items().keySet()), args[1]);
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
