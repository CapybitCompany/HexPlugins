package hex.auctionbazaar.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static PluginConfig load(File dataFolder, FileConfiguration main, Logger logger) {
        boolean enabled = main.getBoolean("enabled", true);
        boolean debug = main.getBoolean("debug", false);
        String prefix = main.getString("prefix", "");
        boolean economyRequired = main.getBoolean("economy.required", true);

        AuctionConfig auction = loadAuction(main);
        Map<String, BazaarItemConfig> items = loadBazaarItems(dataFolder, logger);
        BazaarConfig bazaar = loadBazaar(main, items);
        MessagesConfig messages = loadMessages(dataFolder, logger);

        return new PluginConfig(enabled, debug, prefix, economyRequired, auction, bazaar, messages);
    }

    private static AuctionConfig loadAuction(FileConfiguration c) {
        return new AuctionConfig(
                c.getBoolean("auction.enabled", true),
                c.getLong("auction.default-duration-seconds", 86400L),
                c.getInt("auction.max-active-listings-per-player", 10),
                bd(c.getString("auction.min-price"), "1"),
                bd(c.getString("auction.max-price"), "1000000000"),
                bd(c.getString("auction.listing-fee"), "0"),
                bd(c.getString("auction.sale-fee-percent"), "0"),
                c.getLong("auction.reservation-ttl-seconds", 30L),
                c.getInt("auction.expiry-scan-interval-ticks", 1200),
                c.getString("auction.gui.title", "&8Auction House"),
                Math.max(9, Math.min(45, c.getInt("auction.gui.page-size", 45))),
                c.getString("auction.gui.my-listings-title", "&8My listings"),
                c.getString("auction.gui.claims-title", "&8Claims"),
                c.getString("auction.gui.confirm-title", "&8Confirm"),
                c.getString("auction.permissions.open", "hexauction.open"),
                c.getString("auction.permissions.sell", "hexauction.sell"),
                c.getString("auction.permissions.cancel-own", "hexauction.cancel"),
                c.getString("auction.permissions.admin", "hexauction.admin")
        );
    }

    private static BazaarConfig loadBazaar(FileConfiguration c, Map<String, BazaarItemConfig> items) {
        BazaarConfig.Pricing pricing = new BazaarConfig.Pricing(
                bd(c.getString("bazaar.pricing.elasticity"), "0.5"),
                bd(c.getString("bazaar.pricing.reference-stock"), "10000"),
                bd(c.getString("bazaar.pricing.buy-sell-spread-percent"), "5"),
                bd(c.getString("bazaar.pricing.max-step-per-transaction-percent"), "5")
        );
        return new BazaarConfig(
                c.getBoolean("bazaar.enabled", true),
                c.getBoolean("bazaar.require-plain-item", true),
                pricing,
                c.getString("bazaar.gui.title", "&8Bazaar"),
                c.getString("bazaar.gui.item-title", "&8%display%"),
                c.getString("bazaar.gui.quantity-title", "&8Choose amount"),
                c.getString("bazaar.permissions.open", "hexbazaar.open"),
                c.getString("bazaar.permissions.buy", "hexbazaar.buy"),
                c.getString("bazaar.permissions.sell", "hexbazaar.sell"),
                c.getString("bazaar.permissions.admin", "hexbazaar.admin"),
                items
        );
    }

    private static Map<String, BazaarItemConfig> loadBazaarItems(File dataFolder, Logger logger) {
        File file = new File(dataFolder, "bazaar-items.yml");
        YamlConfiguration yaml = loadYaml(file, "bazaar-items.yml", logger);
        ConfigurationSection items = yaml.getConfigurationSection("items");
        if (items == null) {
            return Map.of();
        }
        Map<String, BazaarItemConfig> out = new LinkedHashMap<>();
        for (String rawKey : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(rawKey);
            if (s == null) continue;
            String key = rawKey.toLowerCase(Locale.ROOT);
            String materialName = s.getString("material", "AIR");
            Material material;
            try {
                material = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                logger.warning("bazaar-items.yml: unknown material '" + materialName + "' for " + key + " - skipping");
                continue;
            }
            if (material == Material.AIR) {
                logger.warning("bazaar-items.yml: AIR is not allowed for " + key + " - skipping");
                continue;
            }
            BazaarItemConfig item = new BazaarItemConfig(
                    key,
                    material,
                    s.getString("display-name", key),
                    s.getString("category", "default"),
                    bd(s.getString("base-price"), "1"),
                    bd(s.getString("min-price"), "0.01"),
                    bd(s.getString("max-price"), "1000000"),
                    Math.max(0L, s.getLong("initial-stock", 0L)),
                    s.getBoolean("buy-enabled", true),
                    s.getBoolean("sell-enabled", true)
            );
            out.put(key, item);
        }
        return Map.copyOf(out);
    }

    private static MessagesConfig loadMessages(File dataFolder, Logger logger) {
        File file = new File(dataFolder, "messages.yml");
        YamlConfiguration yaml = loadYaml(file, "messages.yml", logger);
        Map<String, String> flat = new HashMap<>();
        flatten(yaml, "", flat);
        return new MessagesConfig(flat);
    }

    private static void flatten(ConfigurationSection section, String prefix, Map<String, String> out) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) {
                flatten(nested, path, out);
            } else if (value != null) {
                out.put(path, value.toString());
            }
        }
    }

    private static YamlConfiguration loadYaml(File file, String defaultResource, Logger logger) {
        if (!file.exists()) {
            // Try to extract default from jar resources.
            try (InputStream in = ConfigLoader.class.getResourceAsStream("/" + defaultResource)) {
                if (in != null) {
                    file.getParentFile().mkdirs();
                    java.nio.file.Files.copy(in, file.toPath());
                } else {
                    logger.warning("Resource " + defaultResource + " not found in JAR.");
                }
            } catch (IOException ex) {
                logger.warning("Could not extract " + defaultResource + ": " + ex.getMessage());
            }
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        } catch (Exception ex) {
            logger.warning("Could not load " + file.getName() + ": " + ex.getMessage());
        }
        return yaml;
    }

    private static BigDecimal bd(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return new BigDecimal(fallback);
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            return new BigDecimal(fallback);
        }
    }
}
