package hexleszek;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Map;

public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    public static Component component(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }

    public static String apply(String text, Map<String, String> placeholders) {
        String out = text == null ? "" : text;
        if (placeholders == null || placeholders.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            out = out.replace("{" + entry.getKey() + "}", value)
                    .replace("<" + entry.getKey() + ">", value)
                    .replace("%" + entry.getKey() + "%", value);
        }
        return out;
    }
}
