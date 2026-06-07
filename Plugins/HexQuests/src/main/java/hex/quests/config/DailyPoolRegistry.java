package hex.quests.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DailyPoolRegistry {
    private final Map<String, DailyPool> pools;

    private DailyPoolRegistry(Map<String, DailyPool> pools) {
        this.pools = Map.copyOf(pools);
    }

    public static DailyPoolRegistry load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, DailyPool> loaded = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("daily-pools");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section != null && section.getBoolean("enabled", true)) {
                    loaded.put(id.toLowerCase(java.util.Locale.ROOT), new DailyPool(id, Math.max(0, section.getInt("slots", 1))));
                }
            }
        }
        return new DailyPoolRegistry(loaded);
    }

    public Collection<DailyPool> all() { return pools.values(); }

    public record DailyPool(String id, int slots) {}
}

