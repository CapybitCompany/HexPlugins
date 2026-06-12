package hex.minions.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record AutoSmelterDefinition(
        boolean enabled,
        String requiredSpecialItem,
        Map<String, String> replacements
) {
    public static AutoSmelterDefinition disabled() {
        return new AutoSmelterDefinition(false, "auto_smelter", Map.of());
    }

    public static AutoSmelterDefinition fromConfig(ConfigurationSection section) {
        if (section == null) return disabled();
        Map<String, String> replacements = new LinkedHashMap<>();
        ConfigurationSection map = section.getConfigurationSection("replacements");
        if (map != null) {
            for (String input : map.getKeys(false)) {
                String output = map.getString(input, "");
                if (output == null || output.isBlank()) continue;
                replacements.put(input.toLowerCase(Locale.ROOT), output.toLowerCase(Locale.ROOT));
            }
        }
        return new AutoSmelterDefinition(
                section.getBoolean("enabled", false),
                section.getString("special-item", "auto_smelter"),
                Map.copyOf(replacements)
        );
    }

    public String outputFor(String resourceId) {
        if (!enabled || resourceId == null) return resourceId;
        return replacements.getOrDefault(resourceId.toLowerCase(Locale.ROOT), resourceId);
    }

    public boolean changes(String resourceId) {
        return enabled && resourceId != null && replacements.containsKey(resourceId.toLowerCase(Locale.ROOT));
    }
}
