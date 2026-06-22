package hexnpc.config;

import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.config.ShopMessages;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class HexNpcConfigLoader {

    public HexNpcConfig load(FileConfiguration config) {
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
                config.getInt("render.tablist-remove-delay-ticks", 40)
        );

        ShopConfig shops = loadShopConfig(config.getConfigurationSection("shops"));

        return new HexNpcConfig(enabled, debug, dialogue, proximity, render, shops);
    }

    private ShopConfig loadShopConfig(ConfigurationSection section) {
        if (section == null) {
            return ShopConfig.defaults();
        }
        ShopMessages messages = loadShopMessages(section.getConfigurationSection("messages"));
        return new ShopConfig(
                section.getBoolean("enabled", true),
                section.getBoolean("require-economy", true),
                section.getString("title-format", "&8Sklep: &6<shop>"),
                section.getInt("default-size", 54),
                section.getInt("default-sell-slot", 49),
                section.getBoolean("prevent-selling-custom-items", true),
                messages
        );
    }

    private ShopMessages loadShopMessages(ConfigurationSection section) {
        ShopMessages d = ShopMessages.defaults();
        if (section == null) {
            return d;
        }
        return new ShopMessages(
                section.getString("economy-missing", d.economyMissing()),
                section.getString("shop-not-found", d.shopNotFound()),
                section.getString("inventory-full", d.inventoryFull()),
                section.getString("not-enough-money", d.notEnoughMoney()),
                section.getString("not-enough-items", d.notEnoughItems()),
                section.getString("bought", d.bought()),
                section.getString("sold", d.sold()),
                section.getString("transaction-failed", d.transactionFailed()),
                section.getString("trade-busy", d.tradeBusy())
        );
    }
}
