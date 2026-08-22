package hex.collections.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

public record CollectionScalingDefinition(
        boolean enabled,
        int referenceMembers,
        String category,
        double activeHoursPerMemberL7,
        double manualRatePerPlayer,
        Map<Integer, Long> referenceAddons
) {
    public static CollectionScalingDefinition disabled() {
        return new CollectionScalingDefinition(false, 5, "SPECIAL", 0.0D, 0.0D, Map.of());
    }

    public static CollectionScalingDefinition fromConfig(ConfigurationSection section) {
        if (section == null || !section.getBoolean("enabled", false)) {
            return disabled();
        }
        int referenceMembers = Math.max(1, section.getInt("reference-members", 5));
        Map<Integer, Long> addons = new LinkedHashMap<>();
        ConfigurationSection addonSection = section.getConfigurationSection("reference-addons");
        if (addonSection != null) {
            for (String key : addonSection.getKeys(false)) {
                try {
                    int level = Integer.parseInt(key);
                    if (level > 0) {
                        addons.put(level, Math.max(0L, addonSection.getLong(key, 0L)));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new CollectionScalingDefinition(
                true,
                referenceMembers,
                section.getString("category", "MEDIUM"),
                Math.max(0.0D, section.getDouble("active-hours-per-member-l7", 0.0D)),
                Math.max(0.0D, section.getDouble("manual-rate-per-player", 0.0D)),
                Map.copyOf(addons)
        );
    }

    public long referenceAddonFor(int level) {
        return Math.max(0L, referenceAddons.getOrDefault(level, 0L));
    }

    public long scaledAddonFor(int level, int effectiveMembers) {
        if (!enabled || effectiveMembers <= 0) {
            return 0L;
        }
        long reference = referenceAddonFor(level);
        if (reference <= 0L) {
            return 0L;
        }
        return (long) Math.ceil(reference * (double) effectiveMembers / (double) referenceMembers);
    }
}
