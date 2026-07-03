package hex.minions.advancement;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

public record MinionAdvancementRequirement(
        String type,
        String minionType,
        int minTier,
        String collectionId,
        long minAmount,
        int minLevel,
        int minCount
) {
    public static MinionAdvancementRequirement fromConfig(ConfigurationSection section) {
        if (section == null) {
            return new MinionAdvancementRequirement("manual", "", 1, "", 0L, 1, 1);
        }
        String rawType = section.getString("type", "manual");
        String type = rawType == null ? "manual" : rawType.toLowerCase(Locale.ROOT).replace('_', '-');
        String minionType = section.getString("minion-type", section.getString("minion", ""));
        if (minionType == null) minionType = "";
        String collectionId = section.getString("collection-id", section.getString("collection", ""));
        if (collectionId == null) collectionId = "";
        return new MinionAdvancementRequirement(
                type,
                minionType.toLowerCase(Locale.ROOT),
                Math.max(1, section.getInt("min-tier", section.getInt("tier", 1))),
                collectionId.toLowerCase(Locale.ROOT),
                Math.max(0L, section.getLong("min-amount", section.getLong("amount", section.getLong("required", 0L)))),
                Math.max(1, section.getInt("min-level", section.getInt("level", 1))),
                Math.max(1, section.getInt("min-count", section.getInt("count", section.getInt("types", section.getInt("min-types", 1)))))
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

    public boolean isCollectionAmount() {
        return "collection-amount".equals(type) || "collection".equals(type) || "collection-progress".equals(type);
    }

    public boolean isCollectionLevel() {
        return "collection-level".equals(type) || "collection-tier".equals(type);
    }

    public boolean isCollectionMaxLevel() {
        return "collection-max-level".equals(type) || "collection-highest-level".equals(type) || "collection-complete".equals(type) || "collection-max-tier".equals(type);
    }

    public boolean isAnyCollectionLevel() {
        return "any-collection-level".equals(type) || "first-collection-level".equals(type) || "any-collection-tier".equals(type);
    }

    public boolean isMinionTypeCount() {
        return "minion-type-count".equals(type) || "distinct-minion-types".equals(type) || "unique-minion-types".equals(type) || "minion-types".equals(type);
    }

    public boolean isMinionCount() {
        return "minion-count".equals(type) || "placed-minions".equals(type) || "town-minions".equals(type);
    }
}
