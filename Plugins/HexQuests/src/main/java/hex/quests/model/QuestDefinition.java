package hex.quests.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

public record QuestDefinition(
        String id,
        boolean enabled,
        String type,
        String pool,
        int weight,
        String scope,
        String title,
        List<QuestObjective> objectives,
        List<QuestReward> rewards
) {
    public static QuestDefinition fromConfig(String id, ConfigurationSection section) {
        List<QuestObjective> objectives = new ArrayList<>();
        for (var item : section.getMapList("objectives")) {
            QuestObjective objective = QuestObjective.fromMap(item);
            if (!objective.triggerId().isBlank()) {
                objectives.add(objective);
            }
        }

        List<QuestReward> rewards = new ArrayList<>();
        for (var item : section.getMapList("rewards")) {
            rewards.add(QuestReward.fromMap(item));
        }

        return new QuestDefinition(
                id,
                section.getBoolean("enabled", true),
                section.getString("type", "DAILY"),
                section.getString("pool", "default"),
                Math.max(1, section.getInt("weight", 1)),
                section.getString("scope", "TOWN_PLAYER"),
                section.getString("title", id),
                List.copyOf(objectives),
                List.copyOf(rewards)
        );
    }
}


