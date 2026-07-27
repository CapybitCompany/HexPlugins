package hexnpc.config;

import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.config.ShopLayoutLoader;
import hexnpc.shop.config.ShopMessages;
import hexnpc.shop.model.PlacementMode;
import hexnpc.shop.model.ShopLayout;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class HexNpcConfigLoader {

    public HexNpcConfig load(FileConfiguration config) {
        return load(config, null);
    }

    public HexNpcConfig load(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("enabled", true);
        boolean debug = config.getBoolean("debug", false);

        HexNpcConfig.Dialogue dialogue = new HexNpcConfig.Dialogue(
                config.getInt("dialogue.default-line-delay-ticks", 20),
                config.getInt("dialogue.default-cooldown-ticks", 200),
                config.getString("dialogue.prefix", "")
        );

        HexNpcConfig.Proximity proximity = new HexNpcConfig.Proximity(
                config.getInt("proximity.scan-interval-ticks", 10),
                config.getDouble("proximity.default-radius", 3.0D),
                config.getInt("proximity.default-cooldown-ticks", 600)
        );

        HexNpcConfig.Render render = new HexNpcConfig.Render(
                config.getDouble("render.view-distance-blocks", 48.0D),
                config.getInt("render.tablist-remove-delay-ticks", 40),
                config.getDouble("render.sitting-y-offset", HexNpcConfig.Render.DEFAULT_SITTING_Y_OFFSET)
        );

        HexNpcConfig.Skins skins = loadSkins(config.getConfigurationSection("skins"));

        ShopConfig shops = loadShopConfig(config.getConfigurationSection("shops"), logger);

        return new HexNpcConfig(enabled, debug, dialogue, proximity, render, skins, shops);
    }

    private HexNpcConfig.Skins loadSkins(ConfigurationSection section) {
        if (section == null) {
            return HexNpcConfig.Skins.defaults();
        }
        ConfigurationSection mineskin = section.getConfigurationSection("mineskin");
        if (mineskin == null) {
            return HexNpcConfig.Skins.defaults();
        }
        HexNpcConfig.Skins.MineSkin d = HexNpcConfig.Skins.MineSkin.defaults();
        return new HexNpcConfig.Skins(new HexNpcConfig.Skins.MineSkin(
                mineskin.getBoolean("enabled", d.enabled()),
                mineskin.getString("api-key", d.apiKey()),
                mineskin.getString("user-agent", d.userAgent()),
                mineskin.getString("base-url", d.baseUrl()),
                mineskin.getInt("request-timeout-seconds", d.requestTimeoutSeconds()),
                mineskin.getInt("max-poll-attempts", d.maxPollAttempts()),
                mineskin.getLong("poll-interval-millis", d.pollIntervalMillis())
        ));
    }

    private ShopConfig loadShopConfig(ConfigurationSection section, Logger logger) {
        if (section == null) {
            return ShopConfig.defaults();
        }
        boolean enabled = section.getBoolean("enabled", true);
        boolean requireEconomy = section.getBoolean("require-economy", true);
        String titleFormat = section.getString("title-format", "&8Sklep: &6<shop>");
        boolean preventSelling = section.getBoolean("prevent-selling-custom-items", true);

        int defaultSize = section.getInt("default-size", 54);
        PlacementMode placement = PlacementMode.parse(
                section.getString("default-placement",
                        section.getString("default-layout.placement")), PlacementMode.AUTO);
        ShopLayout base = ShopLayout.defaults(defaultSize);
        ShopLayout defaultLayout = ShopLayoutLoader.load(
                section.getConfigurationSection("default-layout"), base, defaultSize,
                placement, logger, "config default-layout");
        // Kompatybilność wstecz: stary klucz default-sell-slot nadpisuje slot „Sprzedaj".
        if (section.contains("default-sell-slot")) {
            int legacySell = section.getInt("default-sell-slot", defaultLayout.detailSellSlot());
            defaultLayout = defaultLayout.withDetailSellSlot(legacySell).validated(logger, "config default-sell-slot");
        }

        List<Integer> presets = section.contains("quantity-presets")
                ? section.getIntegerList("quantity-presets") : List.of(1, 64);
        boolean enableCustomQuantity = section.getBoolean("enable-custom-quantity", true);
        boolean enableSellAll = section.getBoolean("enable-sell-all", true);
        boolean signEnabled = section.getBoolean("sign-editor.enabled", true);
        int signTimeout = section.getInt("sign-editor.timeout-seconds", 30);
        int signFailover = section.getInt("sign-editor.chat-fallback-seconds", 4);
        int priceScale = section.getInt("price-scale", 2);

        ShopConfig.Confirmation confirmation = loadConfirmation(section.getConfigurationSection("confirmation"));
        ShopConfig.AuditLog auditLog = loadAuditLog(section.getConfigurationSection("audit-log"), logger);

        ShopMessages messages = loadShopMessages(section.getConfigurationSection("messages"));

        return new ShopConfig(enabled, requireEconomy, titleFormat, preventSelling,
                defaultLayout, presets, enableCustomQuantity, enableSellAll,
                signEnabled, signTimeout, signFailover, priceScale, confirmation, auditLog, messages);
    }

    private ShopConfig.Confirmation loadConfirmation(ConfigurationSection section) {
        ShopConfig.Confirmation d = ShopConfig.Confirmation.defaults();
        if (section == null) {
            return d;
        }
        return new ShopConfig.Confirmation(
                section.getBoolean("enabled", d.enabled()),
                section.getInt("threshold", d.threshold()),
                section.getInt("size", d.size()),
                section.getInt("preview-slot", d.previewSlot()),
                section.getInt("confirm-slot", d.confirmSlot()),
                section.getInt("cancel-slot", d.cancelSlot()));
    }

    private ShopConfig.AuditLog loadAuditLog(ConfigurationSection section, Logger logger) {
        ShopConfig.AuditLog d = ShopConfig.AuditLog.defaults();
        if (section == null) {
            return d;
        }
        String rawTable = section.getString("table", d.table());
        ShopConfig.AuditLog result = new ShopConfig.AuditLog(
                section.getBoolean("enabled", d.enabled()),
                rawTable,
                section.getBoolean("log-denied-transactions", d.logDenied()));
        if (logger != null && result.isTableSanitized(rawTable)) {
            logger.warning("HexNPC: nieprawidłowa nazwa tabeli audytu '" + rawTable
                    + "' — użyto bezpiecznej domyślnej '" + result.table() + "'.");
        }
        return result;
    }

    private ShopMessages loadShopMessages(ConfigurationSection section) {
        Map<String, String> overrides = new LinkedHashMap<>();
        if (section != null) {
            for (String key : ShopMessages.defaultValues().keySet()) {
                if (section.contains(key)) {
                    overrides.put(key, section.getString(key));
                }
            }
        }
        return new ShopMessages(overrides);
    }
}
