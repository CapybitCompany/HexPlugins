package hex.core.api.common;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable content identifier shared by config-driven systems.
 * Format: namespace:path, for example hex:miners_scroll or minecraft:diamond.
 */
public record NamespacedId(String namespace, String path) {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]{1,128}");

    public NamespacedId {
        namespace = normalizePart(namespace, "namespace");
        path = normalizePart(path, "path");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid namespace: " + namespace);
        }
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
    }

    public static NamespacedId parse(String raw) {
        return parse(raw, "hex");
    }

    public static NamespacedId parse(String raw, String defaultNamespace) {
        Objects.requireNonNull(raw, "raw");
        String value = raw.trim().toLowerCase(Locale.ROOT);
        int separator = value.indexOf(':');
        if (separator < 0) {
            return new NamespacedId(defaultNamespace, value);
        }
        if (separator == 0 || separator == value.length() - 1 || value.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("Invalid namespaced id: " + raw);
        }
        return new NamespacedId(value.substring(0, separator), value.substring(separator + 1));
    }

    private static String normalizePart(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Blank " + name);
        }
        return normalized;
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}

