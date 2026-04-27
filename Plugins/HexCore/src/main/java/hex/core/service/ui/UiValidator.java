package hex.core.service.ui;

import hex.core.api.config.ValidationResult;
import hex.core.api.config.Validator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class UiValidator implements Validator<UiConfig> {
    @Override
    public ValidationResult validate(UiConfig cfg) {
        List<String> errors = new ArrayList<>();
        if (cfg == null) {
            errors.add("UiConfig is null");
            return ValidationResult.errors(errors);
        }

        if (cfg.getPrefix() == null) errors.add("prefix is null");
        if (cfg.getTemplates() == null) errors.add("templates is null");
        if (cfg.getTemplateArgs() == null) errors.add("templateArgs is null");
        if (cfg.getOverrides() == null) errors.add("overrides is null");
        if (cfg.getPrefixes() == null) errors.add("prefixes is null");
        if (cfg.getPresets() == null) errors.add("presets is null");

        validateTextMap("templates", cfg.getTemplates(), errors);
        validateTextMap("overrides", cfg.getOverrides(), errors);
        validateTextMap("prefixes", cfg.getPrefixes(), errors);

        validateTemplateArgs(cfg, errors);
        validatePresets(cfg, errors);

        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.errors(errors);
    }

    private void validateTextMap(String section, Map<String, String> map, List<String> errors) {
        if (map == null) {
            return;
        }

        for (Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (key == null || key.isBlank()) {
                errors.add(section + " contains empty key");
            }
            if (value == null || value.isBlank()) {
                errors.add(section + " contains empty value for key: " + key);
            }
        }
    }

    private void validateTemplateArgs(UiConfig cfg, List<String> errors) {
        if (cfg.getTemplateArgs() == null) {
            return;
        }

        Set<String> known = new HashSet<>();
        known.addAll(cfg.getTemplates().keySet());
        known.addAll(cfg.getOverrides().keySet());

        for (Map.Entry<String, List<String>> entry : cfg.getTemplateArgs().entrySet()) {
            String templateKey = entry.getKey();
            List<String> argNames = entry.getValue();

            if (templateKey == null || templateKey.isBlank()) {
                errors.add("templateArgs contains empty template key");
                continue;
            }
            if (!known.isEmpty() && !known.contains(templateKey)) {
                errors.add("templateArgs references unknown template: " + templateKey);
            }
            if (argNames == null) {
                errors.add("templateArgs list is null for key: " + templateKey);
                continue;
            }

            Set<String> seen = new HashSet<>();
            for (String arg : argNames) {
                if (arg == null || arg.isBlank()) {
                    errors.add("blank argument name in templateArgs for key: " + templateKey);
                    continue;
                }
                if (!seen.add(arg)) {
                    errors.add("duplicate argument '" + arg + "' in templateArgs for key: " + templateKey);
                }
            }
        }
    }

    private void validatePresets(UiConfig cfg, List<String> errors) {
        if (cfg.getPresets() == null) {
            return;
        }

        for (Map.Entry<String, UiPresetConfig> entry : cfg.getPresets().entrySet()) {
            String id = entry.getKey();
            UiPresetConfig preset = entry.getValue();

            if (id == null || id.isBlank()) {
                errors.add("presets contains empty id");
                continue;
            }
            if (preset == null) {
                errors.add("preset is null for id: " + id);
                continue;
            }

            if (preset.getSound() != null && !preset.getSound().isBlank()) {
                try {
                    org.bukkit.Sound.valueOf(preset.getSound());
                } catch (IllegalArgumentException ignored) {
                    errors.add("preset '" + id + "' has unknown sound: " + preset.getSound());
                }
            }
        }
    }
}
