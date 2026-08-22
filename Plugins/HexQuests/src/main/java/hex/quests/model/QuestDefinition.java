package hex.quests.model;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

public record QuestDefinition(
        String id,
        boolean enabled,
        int weight,
        String title,
        List<String> description,
        ItemDefinition icon,
        List<QuestObjective> objectives,
        QuestReward reward
) {
    public static QuestDefinition fromConfig(String id, ConfigurationSection section) {
        List<QuestObjective> objectives = new ArrayList<>();
        for (var map : section.getMapList("objectives")) {
            QuestObjective objective = QuestObjective.fromMap(map);
            if (!objective.triggerId().isBlank()) objectives.add(objective);
        }
        return new QuestDefinition(
                id,
                section.getBoolean("enabled", true),
                Math.max(1, section.getInt("weight", 100)),
                section.getString("name", id),
                List.copyOf(section.getStringList("description")),
                ItemDefinition.fromConfig(section.getConfigurationSection("icon"), Material.PAPER),
                List.copyOf(objectives),
                QuestReward.fromConfig(section.getConfigurationSection("reward"))
        );
    }
}
