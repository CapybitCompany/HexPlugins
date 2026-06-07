package hex.quests.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;

public record QuestReward(String type, int amount, String source) {
    public static QuestReward fromConfig(ConfigurationSection section) {
        return new QuestReward(
                section.getString("type", ""),
                section.getInt("amount", 0),
                section.getString("source", "quest")
        );
    }

    public static QuestReward fromMap(Map<?, ?> map) {
        Object rawType = map.get("type");
        Object rawAmount = map.get("amount");
        Object rawSource = map.get("source");
        return new QuestReward(
                String.valueOf(rawType != null ? rawType : ""),
                intValue(rawAmount),
                String.valueOf(rawSource != null ? rawSource : "quest")
        );
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}


