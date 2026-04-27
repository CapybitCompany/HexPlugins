package hex.core.service.ui;

import hex.core.api.ui.TemplateDefinition;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class TemplateRegistry {

    private final Map<String, String> defaults = new ConcurrentHashMap<>();
    private final Map<String, List<String>> defaultArgs = new ConcurrentHashMap<>();

    void registerDefaults(String namespace, Map<String, String> templates) {
        if (templates == null) {
            return;
        }
        for (Map.Entry<String, String> e : templates.entrySet()) {
            String fullKey = normalize(namespace, e.getKey());
            String template = e.getValue();
            if (fullKey != null && template != null && !template.isBlank()) {
                defaults.put(fullKey, template);
            }
        }
    }

    void registerDefaultsWithArgs(String namespace, Map<String, TemplateDefinition> templates) {
        if (templates == null) {
            return;
        }
        for (Map.Entry<String, TemplateDefinition> e : templates.entrySet()) {
            String fullKey = normalize(namespace, e.getKey());
            TemplateDefinition def = e.getValue();
            if (fullKey == null || def == null || def.template() == null || def.template().isBlank()) {
                continue;
            }

            defaults.put(fullKey, def.template());
            List<String> args = def.args() == null ? List.of() : List.copyOf(def.args());
            defaultArgs.put(fullKey, args);
        }
    }

    String resolveTemplate(UiConfig cfg, String templateKey) {
        if (cfg.getOverrides() != null) {
            String override = cfg.getOverrides().get(templateKey);
            if (override != null) {
                return override;
            }
        }

        String runtime = defaults.get(templateKey);
        if (runtime != null) {
            return runtime;
        }

        return cfg.getTemplates().get(templateKey);
    }

    List<String> resolveArgs(UiConfig cfg, String templateKey) {
        List<String> runtime = defaultArgs.get(templateKey);
        if (runtime != null) {
            return runtime;
        }
        List<String> fromFile = cfg.getTemplateArgs().get(templateKey);
        return fromFile == null ? List.of() : List.copyOf(fromFile);
    }

    Set<String> allKeys(UiConfig cfg) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(defaults.keySet());
        keys.addAll(cfg.getTemplates().keySet());
        keys.addAll(cfg.getOverrides().keySet());
        return Set.copyOf(keys);
    }

    Set<String> keysForNamespace(UiConfig cfg, String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return allKeys(cfg);
        }
        String prefix = namespace.endsWith(".") ? namespace : namespace + ".";
        Set<String> out = new HashSet<>();
        for (String key : allKeys(cfg)) {
            if (key.startsWith(prefix)) {
                out.add(key);
            }
        }
        return Set.copyOf(out);
    }

    private String normalize(String namespace, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.contains(".")) {
            return key;
        }
        if (namespace == null || namespace.isBlank()) {
            return key;
        }
        return namespace + "." + key;
    }
}
