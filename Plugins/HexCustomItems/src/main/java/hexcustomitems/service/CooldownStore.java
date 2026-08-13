package hexcustomitems.service;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistiert Cooldowns in eine YAML-Datei.
 * Format: {@code <uuid>.<itemId>: expiryEpochMillis}.
 */
public final class CooldownStore {

    private final JavaPlugin plugin;
    private final String fileName;

    public CooldownStore(JavaPlugin plugin, String fileName) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.fileName = (fileName == null || fileName.isBlank()) ? "cooldowns.yml" : fileName;
    }

    public Map<UUID, Map<String, Long>> read() {
        Map<UUID, Map<String, Long>> result = new HashMap<>();
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            return result;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String rawUuid : yaml.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(rawUuid);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Pomijam niepoprawne UUID w " + fileName + ": " + rawUuid);
                continue;
            }
            ConfigurationSection section = yaml.getConfigurationSection(rawUuid);
            if (section == null) {
                continue;
            }
            Map<String, Long> perItem = new HashMap<>();
            for (String itemId : section.getKeys(false)) {
                perItem.put(itemId, section.getLong(itemId));
            }
            if (!perItem.isEmpty()) {
                result.put(playerId, perItem);
            }
        }
        return result;
    }

    public void write(Map<UUID, Map<String, Long>> data) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Long>> playerEntry : data.entrySet()) {
            for (Map.Entry<String, Long> itemEntry : playerEntry.getValue().entrySet()) {
                yaml.set(playerEntry.getKey() + "." + itemEntry.getKey(), itemEntry.getValue());
            }
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Nie udało się utworzyć katalogu wtyczki dla " + fileName);
            }
            yaml.save(new File(plugin.getDataFolder(), fileName));
        } catch (IOException ex) {
            plugin.getLogger().warning("Nie udało się zapisać " + fileName + ": " + ex.getMessage());
        }
    }
}
