package hex.skills.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

public record SkillDefinition(
        String id,
        boolean enabled,
        String displayName,
        String scope,
        int maxLevel,
        long xpPerLevel,
        List<XpSource> xpSources
) {
    public static SkillDefinition fromConfig(String id, ConfigurationSection section) {
        List<XpSource> sources = new ArrayList<>();
        for (var item : section.getMapList("xp-sources")) {
            XpSource source = XpSource.fromMap(item);
            if (!source.triggerId().isBlank()) {
                sources.add(source);
            }
        }
        return new SkillDefinition(
                id,
                section.getBoolean("enabled", true),
                section.getString("display-name", id),
                section.getString("scope", "TOWN_PLAYER"),
                section.getInt("max-level", 100),
                Math.max(1L, section.getLong("xp-per-level", 100L)),
                List.copyOf(sources)
        );
    }

    public int levelForXp(long xp) {
        return Math.min(maxLevel, (int) Math.max(0L, xp / xpPerLevel));
    }
}


