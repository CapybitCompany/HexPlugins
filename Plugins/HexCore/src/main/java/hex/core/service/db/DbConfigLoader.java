package hex.core.service.db;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public final class DbConfigLoader {

    public DbConfig load(File file) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        DbConfig c = new DbConfig();

        c.enabled = y.getBoolean("enabled", true);
        c.required = y.getBoolean("required", false);
        c.debug = y.getBoolean("debug", false);
        c.type = y.getString("type", "mysql");

        c.host = y.getString("host", "");
        c.port = y.getInt("port", 3306);
        c.database = y.getString("database", "");
        c.username = y.getString("username", "");
        c.password = y.getString("password", "");
        c.sqliteFile = y.getString("sqliteFile", "");

        c.pool.maxSize = y.getInt("pool.maxSize", 10);
        c.pool.minIdle = y.getInt("pool.minIdle", 2);
        c.pool.timeoutMs = y.getLong("pool.timeoutMs", 5000);
        c.pool.lifetimeMs = y.getLong("pool.lifetimeMs", 1800000);

        c.options.useSSL = y.getBoolean("options.useSSL", false);
        c.options.serverTimezone = y.getString("options.serverTimezone", "UTC");

        c.tablePrefix = y.getString("tablePrefix", "");

        return c;
    }
}
