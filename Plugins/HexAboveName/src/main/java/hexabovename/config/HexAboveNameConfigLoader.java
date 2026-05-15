package hexabovename.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashSet;
import java.util.Set;

public final class HexAboveNameConfigLoader {

    public HexAboveNameConfig load(FileConfiguration config) {
        HexAboveNameConfig.Render render = new HexAboveNameConfig.Render(
                config.getLong("render.update-interval-ticks", 4L),
                config.getDouble("render.y-offset", 1.80D),
                config.getBoolean("render.show-to-self", false)
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
                config.getBoolean("storage.mysql.options.use-ssl", false),
                config.getString("storage.mysql.options.server-timezone", "UTC")
        );

        HexAboveNameConfig.MySql mySql = new HexAboveNameConfig.MySql(
                config.getString("storage.mysql.host", "127.0.0.1"),
                config.getInt("storage.mysql.port", 3306),
                config.getString("storage.mysql.database", "minecraft_network"),
                config.getString("storage.mysql.username", "root"),
                config.getString("storage.mysql.password", ""),
                config.getString("storage.mysql.table", "player_display_texts"),
                columns,
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

        return new HexAboveNameConfig(render, worlds, storage, messages, limits);
    }
}
