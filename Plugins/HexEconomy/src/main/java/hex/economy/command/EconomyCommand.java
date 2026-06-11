package hex.economy.command;

import hex.core.api.HexApi;
import hex.economy.HexEconomyPlugin;
import hex.economy.config.EconomyConfig;
import hex.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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

public final class EconomyCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ROOT = List.of("balance", "add", "remove", "set", "reload");

    private final HexEconomyPlugin plugin;
    private final HexApi hexApi;
    private final EconomyService service;

    public EconomyCommand(HexEconomyPlugin plugin, HexApi hexApi, EconomyService service) {
        this.plugin = plugin;
        this.hexApi = hexApi;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("money")) {
            handleBalanceAlias(sender, args);
            return true;
        }

        if (args.length == 0) {
            send(sender, cfg().messages().usageMain());
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "balance", "bal", "money", "kasa" -> handleBalance(sender, Arrays.copyOfRange(args, 1, args.length));
            case "add", "give" -> handleAdminChange(sender, "add", args);
            case "remove", "take", "withdraw" -> handleAdminChange(sender, "remove", args);
            case "set" -> handleAdminChange(sender, "set", args);
            case "reload" -> handleReload(sender);
            default -> send(sender, cfg().messages().usageMain());
        }
        return true;
    }

    private void handleBalanceAlias(CommandSender sender, String[] args) {
        handleBalance(sender, args);
    }

    private void handleBalance(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                send(sender, cfg().messages().playerOnly());
                return;
            }
            UUID uuid = player.getUniqueId();
            String name = player.getName();
            hexApi.db().async(() -> service.getOrCreateBalance(uuid, name)).thenAccept(balance ->
                    Bukkit.getScheduler().runTask(plugin, () -> send(sender, cfg().messages().balanceSelf()
                            .replace("{balance}", service.format(balance))
                            .replace("{player}", name)))).exceptionally(ex -> {
                sendDbError(sender, ex);
                return null;
            });
            return;
        }

        if (!sender.hasPermission("hexeconomy.balance.others") && !sender.hasPermission("hexeconomy.admin")) {
            send(sender, cfg().messages().noPermission());
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        UUID uuid = target.getUniqueId();
        String name = target.getName() == null ? args[0] : target.getName();
        hexApi.db().async(() -> service.getOrCreateBalance(uuid, name)).thenAccept(balance ->
                Bukkit.getScheduler().runTask(plugin, () -> send(sender, cfg().messages().balanceOther()
                        .replace("{player}", name)
                        .replace("{balance}", service.format(balance))))).exceptionally(ex -> {
            sendDbError(sender, ex);
            return null;
        });
    }

    private void handleAdminChange(CommandSender sender, String operation, String[] args) {
        if (!sender.hasPermission("hexeconomy.admin")) {
            send(sender, cfg().messages().noPermission());
            return;
        }
        if (args.length < 3) {
            send(sender, cfg().messages().usageAdmin());
            return;
        }
        String targetName = args[1];
        BigDecimal amount = parseAmount(args[2]);
        if (amount == null) {
            send(sender, cfg().messages().invalidAmount().replace("{amount}", args[2]));
            return;
        }
        String reason = args.length >= 4 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "command";
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        UUID uuid = target.getUniqueId();
        String resolvedName = target.getName() == null ? targetName : target.getName();

        hexApi.db().async(() -> switch (operation) {
            case "add" -> service.deposit(uuid, resolvedName, amount, reason);
            case "remove" -> service.withdraw(uuid, resolvedName, amount, reason);
            case "set" -> service.setBalance(uuid, resolvedName, amount, reason);
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        }).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!result.success()) {
                if ("NOT_ENOUGH_FUNDS".equals(result.reason())) {
                    send(sender, cfg().messages().notEnough()
                            .replace("{player}", resolvedName)
                            .replace("{amount}", service.format(amount))
                            .replace("{balance}", service.format(result.balance())));
                } else if ("NEGATIVE_DISABLED".equals(result.reason())) {
                    send(sender, cfg().messages().negativeDisabled());
                } else {
                    send(sender, cfg().messages().invalidAmount().replace("{amount}", amount.toPlainString()));
                }
                return;
            }
            String template = switch (operation) {
                case "add" -> cfg().messages().added();
                case "remove" -> cfg().messages().removed();
                case "set" -> cfg().messages().set();
                default -> cfg().messages().usageAdmin();
            };
            send(sender, template
                    .replace("{player}", resolvedName)
                    .replace("{amount}", service.format(amount))
                    .replace("{balance}", service.format(result.balance()))
                    .replace("{reason}", reason));
        })).exceptionally(ex -> {
            sendDbError(sender, ex);
            return null;
        });
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("hexeconomy.admin")) {
            send(sender, cfg().messages().noPermission());
            return;
        }
        plugin.reloadPluginConfig();
        send(sender, cfg().messages().reloaded());
    }

    private BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim().replace(',', '.'));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private EconomyConfig cfg() {
        return service.config();
    }

    private void sendDbError(CommandSender sender, Throwable throwable) {
        Bukkit.getScheduler().runTask(plugin, () -> send(sender, cfg().messages().dbError()
                .replace("{error}", rootMessage(throwable))));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private void send(CommandSender sender, String message) {
        plugin.sendConfiguredMessage(sender, message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("money")) {
            if (args.length == 1 && sender.hasPermission("hexeconomy.balance.others")) {
                return playerNames(args[0]);
            }
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(ROOT, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (List.of("balance", "bal", "money", "kasa", "add", "give", "remove", "take", "withdraw", "set").contains(sub)) {
                return playerNames(args[1]);
            }
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (List.of("add", "give", "remove", "take", "withdraw", "set").contains(sub)) {
                return filter(List.of("1", "10", "100", "1000"), args[2]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> playerNames(String prefix) {
        List<String> names = new ArrayList<>();
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                names.add(player.getName());
            }
        }
        return names;
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(value);
            }
        }
        return out;
    }
}
