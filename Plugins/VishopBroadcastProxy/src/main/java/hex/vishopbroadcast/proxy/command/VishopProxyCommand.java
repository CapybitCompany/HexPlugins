package hex.vishopbroadcast.proxy.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import hex.vishopbroadcast.proxy.VishopBroadcastProxyPlugin;
import hex.vishopbroadcast.proxy.config.ProxyService;
import hex.vishopbroadcast.proxy.config.ProxySettings;
import hex.vishopbroadcast.proxy.model.PurchaseInput;
import hex.vishopbroadcast.proxy.text.ProxyText;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class VishopProxyCommand implements SimpleCommand {
    private final VishopBroadcastProxyPlugin plugin;
    private final ProxyServer proxyServer;

    public VishopProxyCommand(VishopBroadcastProxyPlugin plugin, ProxyServer proxyServer) {
        this.plugin = plugin;
        this.proxyServer = proxyServer;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        ProxySettings settings = plugin.settings();
        if (settings == null) {
            source.sendMessage(ProxyText.component("<red>VishopBroadcastProxy configuration is unavailable.", Map.of()));
            return;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!source.hasPermission("vishopbroadcast.admin")) {
                send(source, settings, "no-permission", Map.of());
                return;
            }
            if (plugin.reloadConfiguration()) {
                send(source, plugin.settings(), "reloaded", Map.of());
            } else {
                send(source, settings, "db-error", Map.of());
            }
            return;
        }

        if (!source.hasPermission("vishopbroadcast.command")) {
            send(source, settings, "no-permission", Map.of());
            return;
        }
        if (!plugin.ready()) {
            send(source, settings, "not-ready", Map.of());
            return;
        }
        if (args.length < 2) {
            send(source, settings, "usage", Map.of());
            return;
        }

        String playerName = args[0];
        ProxyService service = settings.service(args[1]).orElse(null);
        if (service == null) {
            send(source, settings, "unknown-service", Map.of("service", args[1]));
            return;
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
                send(source, settings, "invalid-price", Map.of());
                return;
            }
        }
        if (price == null && service.priceFromAmountWhenPriceMissing() && amount != null) {
            price = parsePrice(amount);
            if (price == null) {
                send(source, settings, "invalid-price", Map.of());
                return;
            }
        }
        if (service.amountRequired() && amount == null) {
            send(source, settings, "amount-required", Map.of());
            return;
        }
        if (service.priceRequired() && price == null) {
            send(source, settings, "price-required", Map.of());
            return;
        }

        UUID onlineUuid = proxyServer.getPlayer(playerName).map(Player::getUniqueId).orElse(null);
        String createdBy = source instanceof Player player ? player.getUsername() : "PROXY";
        BigDecimal finalPrice = price;
        String finalExternalId = externalId;
        var activeRepository = plugin.repository();
        plugin.executeDatabase(() -> {
            try {
                UUID uuid = onlineUuid;
                if (uuid == null) {
                    uuid = activeRepository.findKnownUuid(playerName).orElseGet(() -> offlineUuid(playerName));
                }
                String info = logInfo(settings, service, playerName, amount, finalPrice);
                PurchaseInput input = new PurchaseInput(
                        finalExternalId,
                        uuid,
                        playerName,
                        service.key(),
                        service.displayName(),
                        amount,
                        finalPrice,
                        info,
                        createdBy
                );
                boolean inserted = activeRepository.savePurchase(input);
                String key = inserted ? "queued" : (finalExternalId == null ? "duplicate-auto" : "duplicate");
                send(source, settings, key, Map.of(
                        "player", playerName,
                        "service", service.key(),
                        "purchase_id", finalExternalId == null ? "" : finalExternalId
                ));
            } catch (Exception exception) {
                plugin.logDatabaseError("Could not save vishop purchase", exception);
                send(source, settings, "db-error", Map.of());
            }
        });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            List<String> values = new ArrayList<>();
            if (invocation.source().hasPermission("vishopbroadcast.admin")) {
                values.add("reload");
            }
            proxyServer.getAllPlayers().forEach(player -> values.add(player.getUsername()));
            return filter(values, args[0]);
        }
        ProxySettings settings = plugin.settings();
        if (args.length == 2 && settings != null) {
            return filter(settings.services().stream().filter(ProxyService::enabled).map(ProxyService::key).toList(), args[1]);
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

    private static String logInfo(ProxySettings settings, ProxyService service, String playerName, String amount, BigDecimal price) {
        String amountPart = amount == null ? "" : ProxyText.render(service.amountPart(), Map.of("amount", amount));
        String priceText = price == null ? "" : price.setScale(settings.priceDecimals(), RoundingMode.HALF_UP).toPlainString();
        String pricePart = price == null ? "" : ProxyText.render(service.pricePart(), Map.of("price", priceText));
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", playerName);
        values.put("service", service.displayName());
        values.put("amount", amount == null ? "" : amount);
        values.put("price", priceText);
        values.put("amount_part", amountPart);
        values.put("price_part", pricePart);
        return ProxyText.render(service.logInfo(), values);
    }

    private static void send(CommandSource source, ProxySettings settings, String key, Map<String, String> values) {
        source.sendMessage(ProxyText.component(settings.message(key), values));
    }

    private static BigDecimal parsePrice(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(input.replace(',', '.'));
            return value.signum() < 0 ? null : value;
        } catch (NumberFormatException ignored) {
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

    private static UUID offlineUuid(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
