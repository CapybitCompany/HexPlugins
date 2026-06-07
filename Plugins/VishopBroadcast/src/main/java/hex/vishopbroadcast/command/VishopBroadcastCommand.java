package hex.vishopbroadcast.command;

import hex.core.api.HexApi;
import hex.vishopbroadcast.VishopBroadcastPlugin;
import hex.vishopbroadcast.config.ConfiguredService;
import hex.vishopbroadcast.config.VishopSettings;
import hex.vishopbroadcast.database.PurchaseRepository;
import hex.vishopbroadcast.model.PurchaseInput;
import hex.vishopbroadcast.text.PlaceholderRenderer;
import hex.vishopbroadcast.text.PurchaseTextFactory;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class VishopBroadcastCommand implements CommandExecutor, TabCompleter {
    private final VishopBroadcastPlugin plugin;
    private final HexApi api;
    private final PurchaseTextFactory textFactory;

    public VishopBroadcastCommand(VishopBroadcastPlugin plugin, HexApi api, PurchaseTextFactory textFactory) {
        this.plugin = plugin;
        this.api = api;
        this.textFactory = textFactory;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("vishopbroadcast.admin")) {
                sendMessage(sender, plugin.settings().message("no-permission"), Map.of());
                return true;
            }
            plugin.reloadPlugin();
            sendMessage(sender, plugin.settings().message("reloaded"), Map.of());
            return true;
        }

        if (!sender.hasPermission("vishopbroadcast.command")) {
            sendMessage(sender, plugin.settings().message("no-permission"), Map.of());
            return true;
        }

        if (args.length < 2) {
            sendMessage(sender, plugin.settings().message("usage"), Map.of());
            return true;
        }

        VishopSettings settings = plugin.settings();
        String playerName = args[0];
        String serviceInput = args[1];
        ConfiguredService service = settings.service(serviceInput).orElse(null);
        if (service == null) {
            sendMessage(sender, settings.message("unknown-service"), Map.of("service", serviceInput));
            return true;
        }

        String amount = args.length >= 3 ? normalizeOptional(args[2]) : null;
        BigDecimal price = null;
        String externalId = null;
        if (args.length >= 4) {
            price = parsePrice(args[3]);
            if (price == null) {
                externalId = normalizeOptional(args[3]);
            }
            if (args.length >= 5) {
                externalId = normalizeOptional(args[4]);
            }
        } else if (args.length == 3 && service.priceFromAmountWhenPriceMissing()) {
            price = parsePrice(args[2]);
            if (price == null) {
                sendMessage(sender, settings.message("invalid-price"), Map.of());
                return true;
            }
        }
        if (price == null && service.priceFromAmountWhenPriceMissing() && amount != null) {
            price = parsePrice(amount);
            if (price == null) {
                sendMessage(sender, settings.message("invalid-price"), Map.of());
                return true;
            }
        }

        if (service.amountRequired() && (amount == null || amount.isBlank())) {
            sendMessage(sender, settings.message("amount-required"), Map.of("service", service.key()));
            return true;
        }
        if (service.priceRequired() && price == null) {
            sendMessage(sender, settings.message("price-required"), Map.of("service", service.key()));
            return true;
        }

        UUID uuid = resolveUuid(playerName);
        String info = textFactory.logInfo(settings, service, uuid, playerName, amount, price);
        PurchaseInput input = new PurchaseInput(
                externalId,
                uuid,
                playerName,
                service.key(),
                service.displayName(),
                amount,
                price,
                info,
                sender instanceof Player player ? player.getName() : sender.getName()
        );

        PurchaseRepository repository = plugin.repository();
        String purchaseId = externalId;
        api.db().async(() -> repository.savePurchase(input))
                .thenAccept(inserted -> Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<String, String> placeholders = Map.of(
                            "player", playerName,
                            "service", service.key(),
                            "purchase_id", purchaseId == null ? "" : purchaseId
                    );
                    String messageKey = Boolean.TRUE.equals(inserted) ? "queued" : (purchaseId == null ? "duplicate-auto" : "duplicate");
                    sendMessage(sender, settings.message(messageKey), placeholders);
                }))
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Could not save vishop purchase: " + ex.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () -> sendMessage(sender, settings.message("db-error"), Map.of()));
                    return null;
                });

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("vishopbroadcast.admin")) {
                completions.add("reload");
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                completions.add(player.getName());
            }
            return filter(completions, args[0]);
        }
        if (args.length == 2) {
            return filter(plugin.settings().services().stream().map(ConfiguredService::key).toList(), args[1]);
        }
        if (args.length == 3) {
            return List.of("1", "10", "100", "1000");
        }
        if (args.length == 4) {
            return List.of("9.99", "19.99", "49.99");
        }
        if (args.length == 5) {
            return List.of("ORDER_ID_Z_VISHOP");
        }
        return List.of();
    }

    private void sendMessage(CommandSender sender, String template, Map<String, String> values) {
        Map<String, String> all = new LinkedHashMap<>(values);
        Component component = textFactory.component(PlaceholderRenderer.render(template, all), all);
        sender.sendMessage(component);
    }

    private static BigDecimal parsePrice(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(input.replace(',', '.'));
            if (value.signum() < 0) {
                return null;
            }
            return value;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String normalizeOptional(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.equals("-") || trimmed.equals("_") || trimmed.equalsIgnoreCase("null") || trimmed.equalsIgnoreCase("none")) {
            return null;
        }
        return trimmed;
    }

    private static UUID resolveUuid(String playerName) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
        return offline.getUniqueId();
    }

    private static List<String> filter(List<String> input, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return input.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}

