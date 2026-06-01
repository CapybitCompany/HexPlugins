package hexabovename.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashSet;
import java.util.Set;

public final class HexAboveNameConfigLoader {

    public HexAboveNameConfig load(FileConfiguration config) {
        HexAboveNameConfig.TitleSystem titleSystem = new HexAboveNameConfig.TitleSystem(
                config.getBoolean("title-system.enabled", true),
                HexAboveNameConfig.UpdateMode.fromRaw(config.getString("title-system.update-mode", "ADAPTIVE")),
                config.getDouble("title-system.y-offset", config.getDouble("render.y-offset", 2.40D)),
                config.getDouble("title-system.movement-threshold", 0.015D),
                config.getLong("title-system.moving-update-interval-ticks", 1L),
                config.getLong("title-system.idle-check-interval-ticks", 12L),
                config.getInt("title-system.teleport-duration-ticks", config.getInt("render.teleport-duration-ticks", 1)),
                config.getInt("title-system.interpolation-duration-ticks", 2),
                config.getBoolean("title-system.shadowed", true),
                config.getBoolean("title-system.show-to-self", config.getBoolean("render.show-to-self", true)),
                config.getString("title-system.default-title", "")
        );

        HexAboveNameConfig.Worlds worlds = new HexAboveNameConfig.Worlds(
                config.getBoolean("worlds.use-whitelist", false),
                new LinkedHashSet<>(config.getStringList("worlds.whitelist"))
        );

        StorageType type = StorageType.fromRaw(config.getString("storage.type", "YAML"));
        long refreshSeconds = Math.max(1L, config.getLong("storage.refresh-interval-seconds", 15L));

        HexAboveNameConfig.Columns columns = new HexAboveNameConfig.Columns(
                config.getString("storage.mysql.columns.player", "player"),
                config.getString("storage.mysql.columns.uuid", "uuid"),
                config.getString("storage.mysql.columns.text", "string")
        );

        HexAboveNameConfig.Options options = new HexAboveNameConfig.Options(
                config.getBoolean("storage.mysql.options.use-ssl", config.getBoolean("storage.mysql.options.useSSL", false)),
                config.getString("storage.mysql.options.server-timezone", config.getString("storage.mysql.options.serverTimezone", "UTC"))
        );

        HexAboveNameConfig.Pool pool = new HexAboveNameConfig.Pool(
                config.getInt("storage.mysql.pool.max-size", config.getInt("storage.mysql.pool.maxSize", 10)),
                config.getInt("storage.mysql.pool.min-idle", config.getInt("storage.mysql.pool.minIdle", 2)),
                config.getLong("storage.mysql.pool.timeout-ms", config.getLong("storage.mysql.pool.timeoutMs", 5000L)),
                config.getLong("storage.mysql.pool.lifetime-ms", config.getLong("storage.mysql.pool.lifetimeMs", 1800000L))
        );

        HexAboveNameConfig.MySql mySql = new HexAboveNameConfig.MySql(
                config.getString("storage.mysql.host", "127.0.0.1"),
                config.getInt("storage.mysql.port", 3306),
                config.getString("storage.mysql.database", "minecraft_network"),
                config.getString("storage.mysql.username", "root"),
                config.getString("storage.mysql.password", ""),
                config.getString("storage.mysql.table", "player_display_texts"),
                columns,
                pool,
                options
        );

        HexAboveNameConfig.Storage storage = new HexAboveNameConfig.Storage(
                type,
                refreshSeconds * 20L,
                config.getString("storage.yaml.file", "users.yml"),
                mySql
        );

        HexAboveNameConfig.Messages messages = new HexAboveNameConfig.Messages(
                config.getString("messages.prefix", "&8[&cHexAboveName&8]&f "),
                config.getString("messages.reloaded", "&aPrzeładowano plugin HexAboveName."),
                config.getString("messages.reload-failed", "&cNie udało się przeładować pluginu. Sprawdź konsolę."),
                config.getString("messages.no-permission", "&cNie masz uprawnień."),
                config.getString("messages.usage", "&cUżycie: /<label> <reload|set|clear>"),
                config.getString("messages.player-not-found", "&cNie znaleziono gracza: &e<player>&c."),
                config.getString("messages.title-set", "&aUstawiono tytuł dla &e<player>&a: &f<title>"),
                config.getString("messages.title-cleared", "&aUsunięto tytuł dla &e<player>&a."),
                config.getString("messages.title-too-long", "&cTytuł jest za długi. Maksymalnie &e<max>&c znaków."),
                config.getString("messages.storage-write-failed", "&cNie udało się zapisać zmian. Sprawdź konsolę.")
        );

        HexAboveNameConfig.Limits limits = new HexAboveNameConfig.Limits(
                config.getInt("limits.max-title-length", 255)
        );

        return new HexAboveNameConfig(titleSystem, worlds, storage, messages, limits);
    }
}
