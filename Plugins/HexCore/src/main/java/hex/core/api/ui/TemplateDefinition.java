package hex.core.api.ui;

import java.util.List;

/**
 * Template metadata used for namespace defaults registration.
 */
public record TemplateDefinition(String template, List<String> args) {

    public static TemplateDefinition of(String template, List<String> args) {
        return new TemplateDefinition(template, args == null ? List.of() : List.copyOf(args));
    }
}

