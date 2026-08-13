package hexcustomitems.service;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerDataService {

    private static final double BASE_MAX_HEALTH = 20.0D;

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Integer> redHearts = new HashMap<>();

    public PlayerDataService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "player-data.yml");
    }

    public void load() {
        redHearts.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        var section = yaml.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String rawUuid : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(rawUuid);
                redHearts.put(uuid, Math.max(0, section.getInt(rawUuid + ".red-hearts", 0)));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Pomijam niepoprawny UUID w player-data.yml: " + rawUuid);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> entry : redHearts.entrySet()) {
            yaml.set("players." + entry.getKey() + ".red-hearts", entry.getValue());
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Nie udało się zapisać player-data.yml: " + ex.getMessage());
        }
    }

    public boolean addRedHeart(Player player, int maxHearts) {
        UUID uuid = player.getUniqueId();
        int current = redHearts.getOrDefault(uuid, 0);
        if (current >= maxHearts) {
            return false;
        }
        redHearts.put(uuid, current + 1);
        apply(player);
        save();
        return true;
    }

    public void apply(Player player) {
        int hearts = redHearts.getOrDefault(player.getUniqueId(), 0);
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) {
            return;
        }
        double target = BASE_MAX_HEALTH + hearts * 2.0D;
        attribute.setBaseValue(target);
        if (player.getHealth() > target) {
            player.setHealth(target);
        }
    }
}
