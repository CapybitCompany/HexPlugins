package hex.collections.config;

import org.bukkit.Material;
import hex.collections.model.CollectionDefinition;
import hex.collections.model.CollectionSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class CollectionRegistry {
    private static final Set<hex.collections.api.CollectionSource> MATERIAL_RULE_SOURCES = Set.of(
            hex.collections.api.CollectionSource.NATURAL_BLOCK_BREAK,
            hex.collections.api.CollectionSource.NATURAL_CROP_HARVEST
    );

    private final Map<String, CollectionDefinition> collections;
    private final List<String> validationErrors;

    private CollectionRegistry(Map<String, CollectionDefinition> collections, List<String> validationErrors) {
        this.collections = Map.copyOf(collections);
        this.validationErrors = List.copyOf(validationErrors);
    }

    public static CollectionRegistry load(File file) {
        Map<String, CollectionDefinition> loaded = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        loadFile(file, loaded, errors);
        File directory = new File(file.getParentFile(), "collections");
        File[] files = directory.listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
            for (File child : files) {
                loadSingleDefinitionFile(child, loaded, errors);
            }
        }
        return new CollectionRegistry(loaded, errors);
    }

    private static void loadFile(File file, Map<String, CollectionDefinition> loaded, List<String> errors) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("collections");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) continue;
                try {
                    CollectionDefinition definition = CollectionDefinition.fromConfig(id, section);
                    validate(definition);
                    if (definition.enabled()) loaded.put(id.toLowerCase(java.util.Locale.ROOT), definition);
                } catch (RuntimeException error) {
                    errors.add(id + " (" + file.getName() + "): " + rootMessage(error));
                }
            }
        }
    }

    private static void loadSingleDefinitionFile(File file, Map<String, CollectionDefinition> loaded, List<String> errors) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String id = yaml.getString("id", file.getName().replaceFirst("(?i)\\.ya?ml$", ""));
        try {
            CollectionDefinition definition = CollectionDefinition.fromConfig(id, yaml);
            validate(definition);
            if (definition.enabled()) loaded.put(id.toLowerCase(java.util.Locale.ROOT), definition);
        } catch (RuntimeException error) {
            errors.add(id + " (" + file.getName() + "): " + rootMessage(error));
        }
    }

    private static void validate(CollectionDefinition definition) {
        if (definition == null || !definition.enabled()) return;
        for (hex.collections.api.CollectionSource source : MATERIAL_RULE_SOURCES) {
            if (!definition.validSources().contains(source)) continue;
            var rule = definition.sourceRules().get(source);
            if (rule == null) {
                throw new IllegalArgumentException("valid_sources contains " + source + " but source_rules." + source + " is missing");
            }
            if (rule.allowedMaterials().isEmpty()) {
                throw new IllegalArgumentException("source_rules." + source + ".allowed_materials must contain at least one valid Material");
            }
        }
    }

    public static boolean requiresMaterialRule(hex.collections.api.CollectionSource source) {
        return source != null && MATERIAL_RULE_SOURCES.contains(source);
    }

    public Collection<CollectionDefinition> all() {
        return collections.values();
    }

    public List<String> validationErrors() {
        return validationErrors;
    }

    public boolean valid() {
        return validationErrors.isEmpty();
    }

    public Optional<CollectionDefinition> find(String idOrAlias) {
        if (idOrAlias == null || idOrAlias.isBlank()) return Optional.empty();
        String key = idOrAlias.toLowerCase(java.util.Locale.ROOT);
        CollectionDefinition direct = collections.get(key);
        if (direct != null) return Optional.of(direct);
        return collections.values().stream()
                .filter(definition -> definition.matchesAlias(idOrAlias))
                .findFirst();
    }

    /**
     * Material-driven sources are deliberately fail-closed. Declaring NATURAL_BLOCK_BREAK or
     * NATURAL_CROP_HARVEST without a matching source rule can never become a wildcard again.
     */
    public List<CollectionDefinition> matching(hex.collections.api.CollectionSource source, Material material) {
        if (source == null) return List.of();
        return collections.values().stream()
                .filter(definition -> definition.validSources().contains(source))
                .filter(definition -> {
                    var rule = definition.sourceRules().get(source);
                    if (requiresMaterialRule(source)) {
                        return rule != null && material != null && rule.materialAllowed(material);
                    }
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

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
