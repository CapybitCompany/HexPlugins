package hexabovename.repository;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class YamlDisplayTextRepository implements DisplayTextRepository {

    private final File file;
    private final Object lock = new Object();
    private Map<String, String> textByLowerName = Map.of();

    public YamlDisplayTextRepository(File file) {
        this.file = file;
    }

    @Override
    public void initialize() {
        synchronized (lock) {
            reloadCacheFromFile();
        }
    }

    @Override
    public Map<UUID, String> loadDisplayTexts(Collection<PlayerSnapshot> players) {
        synchronized (lock) {
            if (players.isEmpty() || textByLowerName.isEmpty()) {
                return Map.of();
            }

            Map<UUID, String> result = new HashMap<>();
            for (PlayerSnapshot player : players) {
                String text = textByLowerName.get(player.name().toLowerCase(Locale.ROOT));
                if (text != null && !text.isBlank()) {
                    result.put(player.uuid(), text);
                }
            }
            return result;
        }
    }

    @Override
    public void upsertDisplayText(UUID uuid, String playerName, String text) throws Exception {
        if (playerName == null || playerName.isBlank() || text == null || text.isBlank()) {
            return;
        }
        synchronized (lock) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection users = config.getConfigurationSection("users");
            if (users == null) {
                users = config.createSection("users");
            }

            String key = removeCaseInsensitiveKey(users, playerName);
            users.set(key + ".text", text);
            saveConfig(config);
            reloadCacheFromFile();
        }
    }

    @Override
    public void clearDisplayText(UUID uuid, String playerName) throws Exception {
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        synchronized (lock) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection users = config.getConfigurationSection("users");
            if (users != null) {
                for (String key : users.getKeys(false)) {
                    if (key.equalsIgnoreCase(playerName)) {
                        users.set(key, null);
                        break;
                    }
                }
                saveConfig(config);
            }
            reloadCacheFromFile();
        }
    }

    private void reloadCacheFromFile() {
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection users = config.getConfigurationSection("users");
        if (users == null) {
            textByLowerName = Map.of();
            return;
        }

        Map<String, String> loaded = new HashMap<>();
        for (String key : users.getKeys(false)) {
            String text = users.getString(key + ".text");
            if (text == null || text.isBlank()) {
                continue;
            }
            loaded.put(key.toLowerCase(Locale.ROOT), text);
        }
        textByLowerName = Map.copyOf(loaded);
    }

    private String removeCaseInsensitiveKey(ConfigurationSection users, String preferredKey) {
        for (String key : users.getKeys(false)) {
            if (key.equalsIgnoreCase(preferredKey)) {
                if (!key.equals(preferredKey)) {
                    users.set(key, null);
                }
                return preferredKey;
            }
        }
        return preferredKey;
    }

    private void saveConfig(YamlConfiguration config) throws IOException {
        config.save(file);
    }
}
