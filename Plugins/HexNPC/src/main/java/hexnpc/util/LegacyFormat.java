package hexnpc.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class LegacyFormat {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private LegacyFormat() {
    }

    public static Component component(String value) {
        if (value == null || value.isEmpty()) {
            return Component.empty();
        }
        return LEGACY.deserialize(value);
    }

    public static String replace(String template, String placeholder, String value) {
        if (template == null) {
            return "";
        }
        return template.replace(placeholder, value == null ? "" : value);
    }
}
