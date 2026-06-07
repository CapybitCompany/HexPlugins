package hex.quests.config;

import hex.quests.model.QuestDefinition;
import hex.quests.model.QuestObjective;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class QuestRegistry {
    private final Map<String, QuestDefinition> quests;

    private QuestRegistry(Map<String, QuestDefinition> quests) {
        this.quests = Map.copyOf(quests);
    }

    public static QuestRegistry load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, QuestDefinition> loaded = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("quests");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section != null) {
                    QuestDefinition definition = QuestDefinition.fromConfig(id, section);
                    if (definition.enabled()) loaded.put(id.toLowerCase(java.util.Locale.ROOT), definition);
                }
            }
        }
        return new QuestRegistry(loaded);
    }

    public Collection<QuestDefinition> all() { return quests.values(); }

    public QuestDefinition get(String questId) { return quests.get(questId.toLowerCase(java.util.Locale.ROOT)); }

    public List<QuestDefinition> dailyByPool(String pool) {
        return quests.values().stream()
                .filter(quest -> quest.type().equalsIgnoreCase("DAILY"))
                .filter(quest -> quest.pool().equalsIgnoreCase(pool))
                .sorted(Comparator.comparing(QuestDefinition::id))
                .toList();
    }

    public Set<String> triggerIds() {
        return quests.values().stream()
                .flatMap(quest -> quest.objectives().stream())
                .map(QuestObjective::triggerId)
                .collect(Collectors.toUnmodifiableSet());
    }
}

