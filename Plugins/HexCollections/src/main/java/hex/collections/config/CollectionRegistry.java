package hex.collections.config;

import org.bukkit.Material;
import hex.collections.model.CollectionDefinition;
import hex.collections.model.CollectionSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class CollectionRegistry {
    private final Map<String, CollectionDefinition> collections;

    private CollectionRegistry(Map<String, CollectionDefinition> collections) {
        this.collections = Map.copyOf(collections);
    }

    public static CollectionRegistry load(File file) {
        Map<String, CollectionDefinition> loaded = new LinkedHashMap<>();
        loadFile(file, loaded);
        File directory = new File(file.getParentFile(), "collections");
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
            for (File child : files) {
                loadSingleDefinitionFile(child, loaded);
            }
        }
        return new CollectionRegistry(loaded);
    }

    private static void loadFile(File file, Map<String, CollectionDefinition> loaded) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("collections");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section != null) {
                    CollectionDefinition definition = CollectionDefinition.fromConfig(id, section);
                    if (definition.enabled()) {
                        loaded.put(id.toLowerCase(java.util.Locale.ROOT), definition);
                    }
                }
            }
        }
    }

    private static void loadSingleDefinitionFile(File file, Map<String, CollectionDefinition> loaded) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String id = yaml.getString("id", file.getName().replaceFirst("(?i)\\.ya?ml$", ""));
        CollectionDefinition definition = CollectionDefinition.fromConfig(id, yaml);
        if (definition.enabled()) {
            loaded.put(id.toLowerCase(java.util.Locale.ROOT), definition);
        }
    }

    public Collection<CollectionDefinition> all() {
        return collections.values();
    }

    public Optional<CollectionDefinition> find(String idOrAlias) {
        if (idOrAlias == null || idOrAlias.isBlank()) {
            return Optional.empty();
        }
        String key = idOrAlias.toLowerCase(java.util.Locale.ROOT);
        CollectionDefinition direct = collections.get(key);
        if (direct != null) {
            return Optional.of(direct);
        }
        return collections.values().stream()
                .filter(definition -> definition.matchesAlias(idOrAlias))
                .findFirst();
    }

    public List<CollectionDefinition> matching(hex.collections.api.CollectionSource source, Material material) {
        if (source == null) {
            return List.of();
        }
        return collections.values().stream()
                .filter(definition -> definition.validSources().contains(source))
                .filter(definition -> {
                    var rule = definition.sourceRules().get(source);
                    return rule == null || material == null || rule.materialAllowed(material);
                })
                .toList();
    }

    public Set<String> triggerIds() {
        return collections.values().stream()
                .flatMap(collection -> collection.sources().stream())
                .map(CollectionSource::triggerId)
                .collect(Collectors.toUnmodifiableSet());
    }
}

