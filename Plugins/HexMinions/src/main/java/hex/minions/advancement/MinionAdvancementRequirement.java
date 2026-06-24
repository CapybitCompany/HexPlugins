package hex.minions.advancement;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

public record MinionAdvancementRequirement(
        String type,
        String minionType,
        int minTier
) {
    public static MinionAdvancementRequirement fromConfig(ConfigurationSection section) {
        if (section == null) {
            return new MinionAdvancementRequirement("manual", "", 1);
        }
        String rawType = section.getString("type", "manual");
        String type = rawType == null ? "manual" : rawType.toLowerCase(Locale.ROOT).replace('_', '-');
        String minionType = section.getString("minion-type", section.getString("minion", ""));
        if (minionType == null) minionType = "";
        return new MinionAdvancementRequirement(
                type,
                minionType.toLowerCase(Locale.ROOT),
                Math.max(1, section.getInt("min-tier", section.getInt("tier", 1)))
        );
    }

    public boolean isTownMember() {
        return "town-member".equals(type) || "town".equals(type);
    }

    public boolean isMinionType() {
        return "minion-type".equals(type) || "minion-placed".equals(type) || "minion".equals(type);
    }

    public boolean isMinionTier() {
        return "minion-tier".equals(type) || "tier".equals(type);
    }
}
