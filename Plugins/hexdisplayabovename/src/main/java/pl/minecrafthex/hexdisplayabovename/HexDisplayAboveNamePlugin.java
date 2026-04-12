package pl.minecrafthex.hexdisplayabovename;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class HexDisplayAboveNamePlugin extends JavaPlugin {

    private File usersFile;
    private FileConfiguration usersConfig;
    private DisplayManager displayManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        createUsersFile();

        this.displayManager = new DisplayManager(this);
        this.displayManager.start();

        ReloadCommand reloadCommand = new ReloadCommand(this);
        if (getCommand("hexdisplayabovename") != null) {
            getCommand("hexdisplayabovename").setExecutor(reloadCommand);
            getCommand("hexdisplayabovename").setTabCompleter(reloadCommand);
        }

        getLogger().info("hexdisplayabovename zostal wlaczony.");
    }

    @Override
    public void onDisable() {
        if (displayManager != null) {
            displayManager.stop();
            displayManager.removeAllDisplays();
        }
        getLogger().info("hexdisplayabovename zostal wylaczony.");
    }

    public void reloadPlugin() {
        reloadConfig();
        loadUsersConfig();

        if (displayManager != null) {
            displayManager.restart();
        }
    }

    private void createUsersFile() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Nie udalo sie utworzyc folderu pluginu.");
        }

        usersFile = new File(getDataFolder(), "users.yml");
        if (!usersFile.exists()) {
            saveResource("users.yml", false);
        }

        loadUsersConfig();
    }

    private void loadUsersConfig() {
        usersConfig = YamlConfiguration.loadConfiguration(usersFile);
    }

    public void saveUsersConfig() {
        if (usersConfig == null || usersFile == null) return;
        try {
            usersConfig.save(usersFile);
        } catch (IOException e) {
            getLogger().severe("Nie udalo sie zapisac users.yml");
            e.printStackTrace();
        }
    }

    public FileConfiguration getUsersConfig() {
        return usersConfig;
    }

    public DisplayManager getDisplayManager() {
        return displayManager;
    }
}