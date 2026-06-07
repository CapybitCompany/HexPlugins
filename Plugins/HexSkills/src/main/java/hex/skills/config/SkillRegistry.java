package hex.skills.config;

import hex.skills.model.SkillDefinition;
import hex.skills.model.XpSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class SkillRegistry {
    private final Map<String, SkillDefinition> skills;

    private SkillRegistry(Map<String, SkillDefinition> skills) {
        this.skills = Map.copyOf(skills);
    }

    public static SkillRegistry load(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Map<String, SkillDefinition> loaded = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("skills");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section != null) {
                    SkillDefinition definition = SkillDefinition.fromConfig(id, section);
                    if (definition.enabled()) {
                        loaded.put(id.toLowerCase(java.util.Locale.ROOT), definition);
                    }
                }
            }
        }
        return new SkillRegistry(loaded);
    }

    public Collection<SkillDefinition> all() {
        return skills.values();
    }

    public Set<String> triggerIds() {
        return skills.values().stream()
                .flatMap(skill -> skill.xpSources().stream())
                .map(XpSource::triggerId)
                .collect(Collectors.toUnmodifiableSet());
    }
}

