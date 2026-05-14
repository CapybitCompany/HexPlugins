package hexpvphandler.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

public final class HexPvPHandlerConfigLoader {

    private final JavaPlugin plugin;

    public HexPvPHandlerConfigLoader(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public HexPvPHandlerConfig load() {
        FileConfiguration config = plugin.getConfig();
        Logger logger = plugin.getLogger();

        String togglePermission = readString(config, "permissions.toggle", "admin.blokujpvp", logger);
        boolean blocked = config.getBoolean("pvp.blocked", false);
        Set<String> exemptWorlds = new LinkedHashSet<>(config.getStringList("pvp.exempt-worlds"));

        HexPvPHandlerConfig.Messages messages = new HexPvPHandlerConfig.Messages(
                readString(config, "messages.prefix", "&8[&cHexPvP&8]&f ", logger),
                readString(config, "messages.no-permission", "&cNie masz uprawnień.", logger),
                readString(config, "messages.pvp-blocked", "&aPvP zostało zablokowane.", logger),
                readString(config, "messages.pvp-unblocked", "&aPvP zostało odblokowane.", logger),
                readString(config, "messages.pvp-already-blocked", "&ePvP jest już zablokowane.", logger),
                readString(config, "messages.pvp-already-unblocked", "&ePvP jest już odblokowane.", logger)
        );

        return new HexPvPHandlerConfig(togglePermission, blocked, exemptWorlds, messages);
    }

    private String readString(FileConfiguration config, String path, String fallback, Logger logger) {
        String value = config.getString(path);
        if (value == null || value.isBlank()) {
            logger.warning("Brak lub pusta wartość '" + path + "'. Używam domyślnej.");
            return fallback;
        }
        return value;
    }
}
