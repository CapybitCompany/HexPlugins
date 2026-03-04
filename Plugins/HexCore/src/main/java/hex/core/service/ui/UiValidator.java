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

        if (cfg.getTemplates() != null) {
            for (Map.Entry<String, String> entry : cfg.getTemplates().entrySet()) {
                String key = entry.getKey();
                String template = entry.getValue();

                if (key == null || key.isBlank()) {
                    errors.add("templates contains empty key");
                }
                if (template == null || template.isBlank()) {
                    errors.add("template is empty for key: " + key);
                }
            }
        }

        if (cfg.getTemplateArgs() != null) {
            for (Map.Entry<String, List<String>> entry : cfg.getTemplateArgs().entrySet()) {
                String templateKey = entry.getKey();
                List<String> argNames = entry.getValue();

                if (templateKey == null || templateKey.isBlank()) {
                    errors.add("templateArgs contains empty template key");
                    continue;
                }
                if (cfg.getTemplates() != null && !cfg.getTemplates().containsKey(templateKey)) {
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

        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.errors(errors);
    }
}
