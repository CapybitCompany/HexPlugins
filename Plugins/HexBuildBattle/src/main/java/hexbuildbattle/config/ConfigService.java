package hexbuildbattle.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

public final class ConfigService {

    private static final List<String> DEFAULT_RESOURCES = List.of(
            "config.yml",
            "messages.yml",
            "themes.yml",
            "ratings.yml",
            "gui.yml"
    );

    private final JavaPlugin plugin;
    private volatile PluginConfig config;
    private volatile FileConfiguration messages;
    private volatile FileConfiguration themes;
    private volatile FileConfiguration ratings;
    private volatile FileConfiguration gui;

    public ConfigService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean reloadAll() {
        ensureDataFolder();
        for (String resource : DEFAULT_RESOURCES) {
            ensureResource(resource);
        }

        try {
            FileConfiguration configYaml = loadYaml("config.yml");
            this.config = PluginConfig.load(configYaml, plugin.getDataFolder().toPath(), plugin.getLogger());
            this.messages = loadYaml("messages.yml");
            this.themes = loadYaml("themes.yml");
            this.ratings = loadYaml("ratings.yml");
            this.gui = loadYaml("gui.yml");
            return true;
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Failed to load HexBuildBattle configuration: " + ex.getMessage());
            ex.printStackTrace();
            return false;
        }
    }

    public PluginConfig config() {
        PluginConfig current = config;
        if (current == null) {
            throw new IllegalStateException("Config has not been loaded yet.");
        }
        return current;
    }

    public FileConfiguration messages() {
        return require(messages, "messages.yml");
    }

    public FileConfiguration themes() {
        return require(themes, "themes.yml");
    }

    public FileConfiguration ratings() {
        return require(ratings, "ratings.yml");
    }

    public FileConfiguration gui() {
        return require(gui, "gui.yml");
    }

    private FileConfiguration require(FileConfiguration configuration, String name) {
        if (configuration == null) {
            throw new IllegalStateException(name + " has not been loaded yet.");
        }
        return configuration;
    }

    private FileConfiguration loadYaml(String fileName) {
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), fileName));
    }

    private void ensureDataFolder() {
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IllegalStateException("Could not create plugin data folder: " + folder);
        }
    }

    private void ensureResource(String fileName) {
        File target = new File(plugin.getDataFolder(), fileName);
        if (target.exists()) {
            return;
        }
        plugin.saveResource(fileName, false);
    }
}
