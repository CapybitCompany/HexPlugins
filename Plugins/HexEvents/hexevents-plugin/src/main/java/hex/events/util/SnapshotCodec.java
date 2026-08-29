package hex.events.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SnapshotCodec {
    private SnapshotCodec() { }

    public static String encode(Map<String, Object> snapshot) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (snapshot != null) {
            for (Map.Entry<String, Object> entry : snapshot.entrySet()) yaml.set("snapshot." + entry.getKey(), entry.getValue());
        }
        return yaml.saveToString();
    }

    public static Map<String, Object> decode(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(raw));
            ConfigurationSection section = yaml.getConfigurationSection("snapshot");
            if (section == null) return Map.of();
            return deep(section);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static Map<String, Object> deep(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection nested) out.put(key, deep(nested));
            else out.put(key, value);
        }
        return out;
    }
}
