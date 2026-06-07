package hex.vishopbroadcast.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class VishopConfigLoader {
    private VishopConfigLoader() {
    }

    public static VishopSettings load(Plugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection messagesSection = config.getConfigurationSection("messages");
        if (messagesSection != null) {
            for (String key : messagesSection.getKeys(false)) {
                messages.put(key, messagesSection.getString(key, ""));
            }
        }

        String defaultAmountPart = config.getString("format.amount-part", " <gray>x</gray><white>{amount}</white>");
        String defaultPricePart = config.getString("format.price-part", " <gray>za</gray> <green>{price} zł</green>");
        String defaultLogInfo = config.getString("format.log-info", "{player} kupił {service}{amount_part}{price_part}");

        Map<String, ConfiguredService> services = new LinkedHashMap<>();
        ConfigurationSection servicesSection = config.getConfigurationSection("services");
        if (servicesSection != null) {
            for (String key : servicesSection.getKeys(false)) {
                ConfigurationSection section = servicesSection.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }

                ServiceDisplay display = loadDisplay(section.getConfigurationSection("display"));
                ConfiguredService service = new ConfiguredService(
                        key,
                        section.getBoolean("enabled", true),
                        section.getStringList("aliases"),
                        section.getString("display-name", key),
                        section.getBoolean("amount-required", false),
                        section.getBoolean("price-required", false),
                        section.getBoolean("price-from-amount-when-price-missing", false),
                        section.getString("amount-part", defaultAmountPart),
                        section.getString("price-part", defaultPricePart),
                        section.getString("log-info", defaultLogInfo),
                        display
                );
                services.put(key.toLowerCase(Locale.ROOT), service);
            }
        }

        return new VishopSettings(
                config.getInt("settings.poll-interval-seconds", 15),
                config.getInt("settings.fetch-limit", 100),
                config.getBoolean("settings.skip-existing-logs-on-startup", true),
                config.getInt("settings.min-chat-only-interval-seconds", 1),
                config.getBoolean("settings.cleanup.enabled", true),
                config.getInt("settings.cleanup.hour", 3),
                config.getInt("settings.cleanup.minute", 0),
                config.getInt("settings.cleanup.retention-days", 30),
                config.getBoolean("settings.dedupe.enabled", true),
                config.getInt("settings.dedupe.window-seconds", 10),
                config.getString("tables.player-totals", "vishop_player_totals"),
                config.getString("tables.purchase-logs", "vishop_purchase_logs"),
                config.getString("tables.purchase-dedupe", "vishop_purchase_dedupe"),
                messages,
                config.getString("format.date-pattern", "yyyy-MM-dd HH:mm:ss"),
                config.getInt("format.price-decimals", 2),
                services
        );
    }

    private static ServiceDisplay loadDisplay(ConfigurationSection section) {
        if (section == null) {
            return new ServiceDisplay(
                    List.of(DisplayChannel.CHAT),
                    3,
                    List.of("<white>{player}</white> <gray>kupił</gray> {service}{amount_part}{price_part}"),
                    "<white>{player}</white> <gray>kupił</gray> {service}",
                    "<gold>NOWY ZAKUP</gold>",
                    "<white>{player}</white> <gray>kupił</gray> {service}",
                    10,
                    60,
                    20
            );
        }

        List<DisplayChannel> channels = new ArrayList<>();
        for (String raw : section.getStringList("channels")) {
            DisplayChannel.parse(raw).ifPresent(channels::add);
        }
        if (channels.isEmpty()) {
            channels.add(DisplayChannel.CHAT);
        }

        List<String> chatLines = section.getStringList("chat.lines");
        if (chatLines.isEmpty()) {
            chatLines = List.of("<white>{player}</white> <gray>kupił</gray> {service}{amount_part}{price_part}");
        }

        return new ServiceDisplay(
                List.copyOf(channels),
                Math.max(1, section.getInt("duration-seconds", 3)),
                List.copyOf(chatLines),
                section.getString("actionbar", "<white>{player}</white> <gray>kupił</gray> {service}"),
                section.getString("title", "<gold>NOWY ZAKUP</gold>"),
                section.getString("subtitle", "<white>{player}</white> <gray>kupił</gray> {service}"),
                Math.max(0, section.getInt("title-times.fade-in-ticks", 10)),
                Math.max(1, section.getInt("title-times.stay-ticks", 60)),
                Math.max(0, section.getInt("title-times.fade-out-ticks", 20))
        );
    }
}

