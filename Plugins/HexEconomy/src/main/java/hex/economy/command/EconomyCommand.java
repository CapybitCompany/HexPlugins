package hex.economy.command;

import hex.core.api.HexApi;
import hex.economy.HexEconomyPlugin;
import hex.economy.api.CurrencyType;
import hex.economy.api.EconomyResult;
import hex.economy.config.EconomyConfig;
import hex.economy.currency.CurrencyAccount;
import hex.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class EconomyCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ROOT = List.of("balance", "add", "remove", "set", "reload");
    private static final List<String> CURRENCIES = List.of("MONEY", "HEX_COINS");

    private final HexEconomyPlugin plugin;
    private final HexApi hexApi;
    private final EconomyService service;

    public EconomyCommand(HexEconomyPlugin plugin, HexApi hexApi, EconomyService service) {
        this.plugin = plugin; this.hexApi = hexApi; this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("money")) {
            // Legacy /money remains MONEY-only.
            handleBalance(sender, args, true);
            return true;
        }
        if (args.length == 0) { send(sender, cfg().messages().usageMain()); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "balance", "bal", "money", "kasa" -> handleBalance(sender, Arrays.copyOfRange(args, 1, args.length), false);
            case "add", "give" -> handleAdminChange(sender, "add", args);
            case "remove", "take", "withdraw" -> handleAdminChange(sender, "remove", args);
            case "set" -> handleAdminChange(sender, "set", args);
            case "reload" -> handleReload(sender);
            default -> send(sender, cfg().messages().usageMain());
        }
        return true;
    }

    private void handleBalance(CommandSender sender, String[] args, boolean legacyMoneyAlias) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) { send(sender, cfg().messages().playerOnly()); return; }
            showBalance(sender, new TargetRef(player.getUniqueId(), player.getName()), CurrencyType.MONEY, true);
            return;
        }
        if (!sender.hasPermission("hexeconomy.balance.others") && !sender.hasPermission("hexeconomy.admin")) {
            send(sender, cfg().messages().noPermission()); return;
        }

        String requestedName = args[0];
        CurrencyType currency = CurrencyType.MONEY;
        if (!legacyMoneyAlias && args.length >= 2) {
            currency = parseCurrency(args[1]);
            if (currency == null || args.length > 2) { send(sender, cfg().messages().usageBalance()); return; }
        } else if (legacyMoneyAlias && args.length > 1) {
            send(sender, cfg().messages().usageBalance()); return;
        }
        final CurrencyType selected = currency;
        if (!service.isCurrencyAvailable(selected)) { sendCurrencyUnavailable(sender, selected); return; }

        resolveTarget(requestedName, selected).thenAccept(target -> {
            if (target == null) { Bukkit.getScheduler().runTask(plugin, () -> sendPlayerNotFound(sender, requestedName, selected)); return; }
            Bukkit.getScheduler().runTask(plugin, () -> showBalance(sender, target, selected, false));
        }).exceptionally(ex -> { sendOperationError(sender, ex); return null; });
    }

    private void showBalance(CommandSender sender, TargetRef target, CurrencyType currency, boolean self) {
        Supplier<BigDecimal> read = () -> currency == CurrencyType.MONEY
                ? service.getOrCreateBalance(target.uuid(), target.name())
                : service.getBalance(target.uuid(), currency);
        execute(currency, read).thenAccept(balance -> Bukkit.getScheduler().runTask(plugin, () -> {
            String template = self ? cfg().messages().balanceSelf() : cfg().messages().balanceOther();
            send(sender, template.replace("{player}", target.name())
                    .replace("{balance}", service.format(currency, balance))
                    .replace("{currency}", service.currencyName(currency)));
        })).exceptionally(ex -> { sendOperationError(sender, ex); return null; });
    }

    private void handleAdminChange(CommandSender sender, String operation, String[] args) {
        if (!sender.hasPermission("hexeconomy.admin")) { send(sender, cfg().messages().noPermission()); return; }
        if (args.length < 3) { send(sender, cfg().messages().usageAdmin()); return; }
        String targetName = args[1];
        BigDecimal amount = parseAmount(args[2]);
        if (amount == null) { send(sender, cfg().messages().invalidAmount().replace("{amount}", args[2])); return; }

        CurrencyType currency = CurrencyType.MONEY;
        int reasonStart = 3;
        // Backward compatibility: arg #4 is a currency ONLY when it exactly matches a known token.
        // Old commands such as /smpeconomy add Steve 5 quest reward still mean MONEY with reason "quest reward".
        if (args.length >= 4) {
            CurrencyType parsed = parseCurrency(args[3]);
            if (parsed != null) { currency = parsed; reasonStart = 4; }
        }
        String reason = args.length > reasonStart ? String.join(" ", Arrays.copyOfRange(args, reasonStart, args.length)) : "command";
        final CurrencyType selected = currency;
        if (!service.isCurrencyAvailable(selected)) { sendCurrencyUnavailable(sender, selected); return; }

        resolveTarget(targetName, selected).thenAccept(target -> {
            if (target == null) { Bukkit.getScheduler().runTask(plugin, () -> sendPlayerNotFound(sender, targetName, selected)); return; }
            Supplier<EconomyResult> operationCall = () -> switch (operation) {
                case "add" -> service.deposit(target.uuid(), target.name(), selected, amount, reason);
                case "remove" -> service.withdraw(target.uuid(), target.name(), selected, amount, reason);
                case "set" -> service.setBalance(target.uuid(), target.name(), selected, amount, reason);
                default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
            };
            execute(selected, operationCall).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () ->
                    sendAdminResult(sender, target, selected, operation, amount, reason, result)))
                    .exceptionally(ex -> { sendOperationError(sender, ex); return null; });
        }).exceptionally(ex -> { sendOperationError(sender, ex); return null; });
    }

    private void sendAdminResult(CommandSender sender, TargetRef target, CurrencyType currency, String operation,
                                 BigDecimal amount, String reason, EconomyResult result) {
        if (!result.success()) {
            switch (result.reason()) {
                case "NOT_ENOUGH_FUNDS" -> send(sender, cfg().messages().notEnough()
                        .replace("{player}", target.name())
                        .replace("{amount}", service.format(currency, amount))
                        .replace("{balance}", service.format(currency, result.balance())));
                case "NEGATIVE_DISABLED" -> send(sender, cfg().messages().negativeDisabled());
                case "CURRENCY_UNAVAILABLE" -> sendCurrencyUnavailable(sender, currency);
                case "PLAYER_NOT_FOUND" -> sendPlayerNotFound(sender, target.name(), currency);
                case "BALANCE_LIMIT" -> send(sender, "&cSaldo przekroczyłoby limit providera " + service.currencyName(currency) + ".");
                case "PROVIDER_ERROR" -> send(sender, "&cProvider waluty " + service.currencyName(currency) + " zwrócił błąd.");
                default -> send(sender, cfg().messages().invalidAmount().replace("{amount}", amount.toPlainString()));
            }
            return;
        }
        String template = switch (operation) {
            case "add" -> cfg().messages().added();
            case "remove" -> cfg().messages().removed();
            case "set" -> cfg().messages().set();
            default -> cfg().messages().usageAdmin();
        };
        send(sender, template.replace("{player}", target.name())
                .replace("{amount}", service.format(currency, amount))
                .replace("{balance}", service.format(currency, result.balance()))
                .replace("{currency}", service.currencyName(currency))
                .replace("{reason}", reason));
    }

    private CompletableFuture<TargetRef> resolveTarget(String requestedName, CurrencyType currency) {
        TargetRef online = findOnlineTarget(requestedName);
        if (online != null) return CompletableFuture.completedFuture(online);
        Supplier<TargetRef> lookup = () -> service.findExistingAccountByName(requestedName, currency)
                .map(account -> new TargetRef(account.uuid(), account.playerName() == null || account.playerName().isBlank() ? requestedName : account.playerName()))
                .orElse(null);
        return execute(currency, lookup);
    }

    private TargetRef findOnlineTarget(String requestedName) {
        if (requestedName == null || requestedName.isBlank()) return null;
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getName().equalsIgnoreCase(requestedName)) return new TargetRef(player.getUniqueId(), player.getName());
        return null;
    }

    private <T> CompletableFuture<T> execute(CurrencyType currency, Supplier<T> work) {
        // MONEY talks to SQL and stays off the main thread. XConomy owns its own state/API and is invoked synchronously.
        if (currency == CurrencyType.MONEY) return hexApi.db().async(work);
        try { return CompletableFuture.completedFuture(work.get()); }
        catch (Throwable ex) { return CompletableFuture.failedFuture(ex); }
    }

    private void sendPlayerNotFound(CommandSender sender, String name, CurrencyType currency) {
        send(sender, "&cNie znaleziono gracza &e" + name + "&c w " + service.currencyName(currency) + ".");
    }
    private void sendCurrencyUnavailable(CommandSender sender, CurrencyType currency) { send(sender, "&cWaluta &e" + currency + "&c jest obecnie niedostępna."); }
    private record TargetRef(UUID uuid, String name) {}

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("hexeconomy.admin")) { send(sender, cfg().messages().noPermission()); return; }
        plugin.reloadPluginConfig(); send(sender, cfg().messages().reloaded());
    }

    private CurrencyType parseCurrency(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return CurrencyType.valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return new BigDecimal(raw.trim().replace(',', '.')); } catch (NumberFormatException exception) { return null; }
    }
    private EconomyConfig cfg() { return service.config(); }
    private void sendOperationError(CommandSender sender, Throwable throwable) {
        Bukkit.getScheduler().runTask(plugin, () -> send(sender, cfg().messages().dbError().replace("{error}", rootMessage(throwable))));
    }
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
    private void send(CommandSender sender, String message) { plugin.sendConfiguredMessage(sender, message); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("money")) {
            if (args.length == 1 && sender.hasPermission("hexeconomy.balance.others")) return playerNames(args[0]);
            return Collections.emptyList();
        }
        if (args.length == 1) return filter(ROOT, args[0]);
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (List.of("balance", "bal", "money", "kasa", "add", "give", "remove", "take", "withdraw", "set").contains(sub)) return playerNames(args[1]);
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (List.of("balance", "bal", "money", "kasa").contains(sub)) return filter(CURRENCIES, args[2]);
            if (List.of("add", "give", "remove", "take", "withdraw", "set").contains(sub)) return filter(List.of("1", "10", "100", "1000"), args[2]);
        }
        if (args.length == 4) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (List.of("add", "give", "remove", "take", "withdraw", "set").contains(sub)) return filter(CURRENCIES, args[3]);
        }
        return Collections.emptyList();
    }

    private List<String> playerNames(String prefix) {
        List<String> names = new ArrayList<>(); String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) names.add(player.getName());
        return names;
    }
    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT); List<String> out = new ArrayList<>();
        for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(value);
        return out;
    }
}
