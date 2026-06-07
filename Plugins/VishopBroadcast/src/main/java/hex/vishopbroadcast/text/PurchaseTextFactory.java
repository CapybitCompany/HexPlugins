package hex.vishopbroadcast.text;

import hex.vishopbroadcast.config.ConfiguredService;
import hex.vishopbroadcast.config.VishopSettings;
import hex.vishopbroadcast.model.PurchaseRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class PurchaseTextFactory {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacyAmpersand = LegacyComponentSerializer.legacyAmpersand();

    public Map<String, String> placeholders(
            VishopSettings settings,
            ConfiguredService service,
            UUID uuid,
            String player,
            String amount,
            BigDecimal price,
            String info,
            LocalDateTime date
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", player == null ? "" : player);
        values.put("uuid", uuid == null ? "" : uuid.toString());
        values.put("service", service == null ? "" : service.displayName());
        values.put("service_raw", service == null ? "" : service.key());
        values.put("amount", amount == null ? "" : amount);
        values.put("price", formatPrice(settings, price));
        values.put("date", date == null ? "" : settings.dateFormatter().format(date));
        values.put("server", Bukkit.getServer().getName());

        String amountPartTemplate = service == null ? "{amount}" : service.amountPartTemplate();
        String pricePartTemplate = service == null ? "{price}" : service.pricePartTemplate();
        values.put("amount_part", amount == null || amount.isBlank() ? "" : PlaceholderRenderer.render(amountPartTemplate, values));
        values.put("price_part", price == null ? "" : PlaceholderRenderer.render(pricePartTemplate, values));
        values.put("info", info == null ? "" : info);
        return values;
    }

    public String logInfo(VishopSettings settings, ConfiguredService service, UUID uuid, String player, String amount, BigDecimal price) {
        Map<String, String> values = placeholders(settings, service, uuid, player, amount, price, "", LocalDateTime.now());
        return PlaceholderRenderer.render(service.logInfoTemplate(), values);
    }

    public Map<String, String> placeholdersForRecord(VishopSettings settings, ConfiguredService service, PurchaseRecord record) {
        return placeholders(
                settings,
                service,
                record.playerUuid(),
                record.playerName(),
                record.amount(),
                record.price(),
                record.broadcastInfo(),
                record.purchaseTime()
        );
    }

    public Component component(String template, Map<String, String> placeholders) {
        String rendered = PlaceholderRenderer.render(template, placeholders);
        if (rendered.indexOf('&') >= 0 && rendered.indexOf('<') < 0) {
            return legacyAmpersand.deserialize(rendered);
        }
        return miniMessage.deserialize(rendered);
    }

    public String plain(String template, Map<String, String> placeholders) {
        return PlaceholderRenderer.render(template, placeholders)
                .replaceAll("<[^>]+>", "")
                .replace('&', '§');
    }

    private String formatPrice(VishopSettings settings, BigDecimal price) {
        if (price == null) {
            return "";
        }
        return price.setScale(settings.priceDecimals(), RoundingMode.HALF_UP).toPlainString();
    }
}

