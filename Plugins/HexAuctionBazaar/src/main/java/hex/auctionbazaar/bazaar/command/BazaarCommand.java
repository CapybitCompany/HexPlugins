package hex.auctionbazaar.bazaar.command;

import hex.auctionbazaar.HexAuctionBazaarPlugin;
import hex.auctionbazaar.audit.model.AuditAction;
import hex.auctionbazaar.bazaar.gui.BazaarMainGui;
import hex.auctionbazaar.bazaar.gui.BazaarOrdersGui;
import hex.auctionbazaar.bazaar.model.BazaarOrder;
import hex.auctionbazaar.bazaar.model.OrderSide;
import hex.auctionbazaar.bazaar.service.BazaarOrderService;
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

import java.math.BigDecimal;
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
        BazaarConfig cfg = plugin.config().bazaar();
        // reload musi działać nawet przy bazaar.enabled:false (naprawa konfiguracji).
        String sub0 = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        // Jednolita bramka „enabled" (punkt #4): globalny tryb konserwacji (enabled:false) ORAZ
        // feature-specific disable (bazaar.enabled:false) blokują NOWĄ komercję, ale przepuszczają akcje
        // ODZYSKU (reload, przegląd/anulowanie własnych zleceń) - dalej chronione permisją i bramką DB,
        // więc środki/przedmioty gracza się nie zablokują.
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
        // reload działa nawet gdy baza/schema nie są gotowe (naprawa konfiguracji).
        if (!sub0.equals("reload")) {
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
            case "orders" -> handleOrders(sender);
            case "order" -> handleOrderSub(sender, args);
            case "buyorder" -> handleBuyOrder(sender, args);
            case "selloffer" -> handleSellOffer(sender, args);
            default -> true;
        };
    }

    /**
     * Podkomendy dozwolone w globalnym trybie konserwacji (enabled:false): reload oraz akcje ODZYSKU
     * (przegląd i anulowanie własnych zleceń), żeby nie uwięzić środków/przedmiotów. Komercyjne wejścia
     * (przeglądanie prowadzące do kupna, buy/sell/buyorder/selloffer) - zablokowane.
     */
    static boolean maintenanceAllowed(String sub0) {
        return switch (sub0) {
            case "reload", "orders", "order" -> true;
            default -> false;
        };
    }

    /** Wynik jednolitej bramki „enabled" dla podkomendy (punkt #4). */
    enum CommandGate { ALLOW, MAINTENANCE, FEATURE_DISABLED }

    /**
     * Czysta, testowalna bramka „enabled" (punkt #4): akcje ODZYSKU ({@link #maintenanceAllowed}) są ZAWSZE
     * przepuszczane (dalej chronione permisją i bramką DB), także przy feature-specific disable
     * (bazaar.enabled:false). Dla komercyjnych wejść globalny tryb konserwacji ma pierwszeństwo
     * (MAINTENANCE) przed wyłączoną funkcją (FEATURE_DISABLED). Ujednolica semantykę obu przełączników.
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
        if (!sender.hasPermission(plugin.config().bazaar().permAdmin()) && !sender.isOp()) {
            plugin.messages().send(sender, "common.no-permission");
            return true;
        }
        plugin.reloadAllConfigs();
        plugin.messages().send(sender, "common.reloaded");
        plugin.auditService().log(plugin.auditService().builder()
                .actor(sender instanceof Player p ? p.getUniqueId() : null,
                       sender.getName())
                .action(AuditAction.ADMIN_RELOAD)
                .market(AuditAction.MARKET_ADMIN)
                .result(AuditAction.RESULT_OK));
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
                Bukkit.getScheduler().runTask(plugin, () -> reportBuy(p, key, outcome)));
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

    private boolean handleOrders(CommandSender sender) {
        BazaarConfig cfg = plugin.config().bazaar();
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "common.must-be-player");
            return true;
        }
        if (!p.hasPermission(cfg.permOrders())) {
            plugin.messages().send(sender, "common.no-permission");
            return true;
        }
        plugin.orderService().listAll(p.getUniqueId(), 20).thenAccept(list ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (list.isEmpty()) {
                        plugin.messages().send(p, "bazaar.order-list-empty");
                        return;
                    }
                    plugin.messages().send(p, "bazaar.order-list-header");
                    for (BazaarOrder o : list) {
                        plugin.messages().send(p, "bazaar.order-list-line", placeholders(
                                "id", String.valueOf(o.id()),
                                "side", plugin.messages().raw(o.side() == OrderSide.BUY
                                        ? "bazaar.order-side-buy" : "bazaar.order-side-sell", null),
                                "amount", o.amountRemaining() + "/" + o.amountTotal(),
                                "item", o.itemKey(),
                                "price", plugin.economy().format(o.pricePerUnit()),
                                "state", plugin.messages().raw("bazaar.order-state."
                                        + o.state().name().toLowerCase(Locale.ROOT), null)));
                    }
                }));
        return true;
    }

    private boolean handleOrderSub(CommandSender sender, String[] args) {
        BazaarConfig cfg = plugin.config().bazaar();
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "common.must-be-player");
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
            if (!p.hasPermission(cfg.permOrderCancel())) {
                plugin.messages().send(sender, "common.no-permission");
                return true;
            }
            if (args.length < 3) {
                plugin.messages().send(p, "bazaar.order-not-found", placeholders("id", "?"));
                return true;
            }
            long id;
            try {
                id = Long.parseLong(args[2]);
            } catch (NumberFormatException ex) {
                plugin.messages().send(p, "bazaar.order-not-found", placeholders("id", args[2]));
                return true;
            }
            plugin.orderService().cancel(p, id).thenAccept(res ->
                    Bukkit.getScheduler().runTask(plugin, () -> reportCancel(p, id, res)));
            return true;
        }
        // fallback: open GUI
        BazaarOrdersGui.open(plugin, p, () -> plugin.config().bazaar(),
                plugin.bazaarService(), plugin.economy(), plugin.messages());
        return true;
    }

    private boolean handleBuyOrder(CommandSender sender, String[] args) {
        BazaarConfig cfg = plugin.config().bazaar();
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "common.must-be-player");
            return true;
        }
        if (!p.hasPermission(cfg.permOrderBuy())) {
            plugin.messages().send(sender, "common.no-permission");
            return true;
        }
        if (args.length < 4) {
            plugin.messages().send(p, "bazaar.invalid-quantity");
            return true;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
        long amount;
        BigDecimal price;
        try {
            amount = Long.parseLong(args[2]);
            price = new BigDecimal(args[3]);
        } catch (NumberFormatException ex) {
            plugin.messages().send(p, "bazaar.invalid-quantity");
            return true;
        }
        plugin.orderService().placeBuyOrder(p, key, amount, price).thenAccept(outcome ->
                Bukkit.getScheduler().runTask(plugin,
                        () -> reportPlace(p, key, amount, price, outcome, OrderSide.BUY)));
        return true;
    }

    private boolean handleSellOffer(CommandSender sender, String[] args) {
        BazaarConfig cfg = plugin.config().bazaar();
        if (!(sender instanceof Player p)) {
            plugin.messages().send(sender, "common.must-be-player");
            return true;
        }
        if (!p.hasPermission(cfg.permOrderSell())) {
            plugin.messages().send(sender, "common.no-permission");
            return true;
        }
        if (args.length < 4) {
            plugin.messages().send(p, "bazaar.invalid-quantity");
            return true;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
        long amount;
        BigDecimal price;
        try {
            amount = Long.parseLong(args[2]);
            price = new BigDecimal(args[3]);
        } catch (NumberFormatException ex) {
            plugin.messages().send(p, "bazaar.invalid-quantity");
            return true;
        }
        plugin.orderService().placeSellOffer(p, key, amount, price).thenAccept(outcome ->
                Bukkit.getScheduler().runTask(plugin,
                        () -> reportPlace(p, key, amount, price, outcome, OrderSide.SELL)));
        return true;
    }

    private void reportPlace(Player p, String key, long amount, BigDecimal price,
                              BazaarOrderService.PlaceOutcome outcome, OrderSide side) {
        switch (outcome.result()) {
            case OK -> {
                String msg = side == OrderSide.BUY
                        ? "bazaar.order-placed-buy" : "bazaar.order-placed-sell";
                plugin.messages().send(p, msg, placeholders(
                        "id", String.valueOf(outcome.orderId()),
                        "amount", String.valueOf(amount),
                        "item", key,
                        "price", plugin.economy().format(price),
                        "total", plugin.economy().format(outcome.totalReserved())));
            }
            case UNKNOWN_ITEM -> plugin.messages().send(p, "bazaar.unknown-item",
                    placeholders("key", key));
            case INVALID_QTY -> plugin.messages().send(p, "bazaar.invalid-quantity");
            case INVALID_PRICE -> {
                BazaarConfig cfg = plugin.config().bazaar();
                String min = cfg.item(key).map(i -> i.minPrice().toPlainString()).orElse("?");
                String max = cfg.item(key).map(i -> i.maxPrice().toPlainString()).orElse("?");
                plugin.messages().send(p, "bazaar.invalid-price",
                        placeholders("min", min, "max", max));
            }
            case TOO_MANY_OPEN -> plugin.messages().send(p, "bazaar.order-too-many",
                    placeholders("max", String.valueOf(plugin.config().bazaar().maxOrdersPerPlayer())));
            case NOT_ENOUGH_MONEY -> plugin.messages().send(p, "bazaar.not-enough-money");
            case NOT_ENOUGH_ITEMS -> plugin.messages().send(p, "bazaar.not-enough-items",
                    placeholders("item", key));
            case ECONOMY_UNAVAILABLE -> plugin.messages().send(p, "common.economy-missing");
            case ECONOMY_ERROR -> plugin.messages().send(p, "common.economy-error");
            case DB_FAILED -> plugin.messages().send(p, "common.schema-not-ready");
            case FEATURE_DISABLED -> plugin.messages().send(p, "bazaar.order-feature-disabled");
            // Krytyczny stan kompensacji: dla SELL nie udało się zwrócić przedmiotów, dla BUY - środków.
            case COMPENSATION_FAILED -> plugin.messages().send(p, side == OrderSide.BUY
                    ? "bazaar.order-buy-refund-failed" : "bazaar.order-return-failed");
            case NO_PERMISSION -> plugin.messages().send(p, "common.no-permission");
            case BUSY -> plugin.messages().send(p, "bazaar.order-busy");
        }
    }

    private void reportCancel(Player p, long id, BazaarOrderService.CancelResult res) {
        switch (res) {
            case OK -> plugin.messages().send(p, "bazaar.order-cancelled",
                    placeholders("id", String.valueOf(id)));
            case NOT_FOUND -> plugin.messages().send(p, "bazaar.order-not-found",
                    placeholders("id", String.valueOf(id)));
            case NOT_OWNER -> plugin.messages().send(p, "bazaar.order-not-yours");
            case NOT_OPEN -> plugin.messages().send(p, "bazaar.order-not-open");
            case NO_PERMISSION -> plugin.messages().send(p, "common.no-permission");
            case DB_FAILED -> plugin.messages().send(p, "common.schema-not-ready");
        }
    }

    private void reportBuy(Player p, String key, BazaarService.BuyOutcome outcome) {
        switch (outcome.result()) {
            case OK -> {
                plugin.messages().send(p, "bazaar.bought", placeholders(
                        "amount", String.valueOf(outcome.deliveredAmount()),
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
            case ECONOMY_ERROR -> plugin.messages().send(p, "common.economy-error");
            case INVALID_QTY -> plugin.messages().send(p, "bazaar.invalid-quantity");
            case DB_FAILED -> plugin.messages().send(p, "common.db-error");
            case REFUNDED -> plugin.messages().send(p, "bazaar.buy-refunded");
            case REFUND_PENDING -> plugin.messages().send(p, "bazaar.buy-refund-pending");
            case OVERPAY_REFUND_PENDING -> {
                plugin.messages().send(p, "bazaar.bought", placeholders(
                        "amount", String.valueOf(outcome.deliveredAmount()),
                        "item", key,
                        "total", plugin.economy().format(outcome.total())));
                if (outcome.wentToClaim()) {
                    plugin.messages().send(p, "bazaar.inventory-full-claim");
                }
                plugin.messages().send(p, "bazaar.overpay-refund-pending");
            }
            case COMPENSATION_FAILED -> plugin.messages().send(p, "bazaar.buy-critical-error");
            case FEATURE_DISABLED -> plugin.messages().send(p, "common.feature-disabled");
            case NO_PERMISSION -> plugin.messages().send(p, "common.no-permission");
        }
    }

    private void reportSell(Player p, String key, int qty, BazaarService.SellOutcome outcome) {
        switch (outcome.result()) {
            case OK -> plugin.messages().send(p, "bazaar.sold", placeholders(
                    "amount", String.valueOf(outcome.amountSold()),
                    "item", key,
                    "total", plugin.economy().format(outcome.total())));
            case OK_PENDING_CLAIM -> {
                plugin.messages().send(p, "bazaar.sold", placeholders(
                        "amount", String.valueOf(outcome.amountSold()),
                        "item", key,
                        "total", plugin.economy().format(outcome.total())));
                plugin.messages().send(p, "bazaar.sell-pending-claim");
            }
            case OK_REST_CLAIMED -> {
                plugin.messages().send(p, "bazaar.sold", placeholders(
                        "amount", String.valueOf(outcome.amountSold()),
                        "item", key,
                        "total", plugin.economy().format(outcome.total())));
                plugin.messages().send(p, "bazaar.sell-rest-claimed");
            }
            case RETURN_FAILED -> plugin.messages().send(p, "bazaar.sell-return-failed");
            case FEATURE_DISABLED -> plugin.messages().send(p, "common.feature-disabled");
            case NO_PERMISSION -> plugin.messages().send(p, "common.no-permission");
            case NOTHING_SOLD -> plugin.messages().send(p, "bazaar.nothing-sold");
            case PAYOUT_FAILED -> plugin.messages().send(p, "bazaar.sell-payout-failed");
            case NOT_ENOUGH_ITEMS -> plugin.messages().send(p, "bazaar.not-enough-items",
                    placeholders("item", key));
            case UNKNOWN_ITEM -> plugin.messages().send(p, "bazaar.unknown-item",
                    placeholders("key", key));
            case SELL_DISABLED -> plugin.messages().send(p, "bazaar.sell-disabled");
            case ECONOMY_UNAVAILABLE -> plugin.messages().send(p, "common.economy-missing");
            case INVALID_QTY -> plugin.messages().send(p, "bazaar.invalid-quantity");
            case DB_FAILED -> plugin.messages().send(p, "common.db-error");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("buy", "sell", "orders", "order",
                    "buyorder", "selloffer", "reload"), args[0]);
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "buy":
                case "sell":
                case "buyorder":
                case "selloffer":
                    return filter(new ArrayList<>(plugin.config().bazaar().items().keySet()), args[1]);
                case "order":
                    return filter(List.of("cancel"), args[1]);
            }
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
