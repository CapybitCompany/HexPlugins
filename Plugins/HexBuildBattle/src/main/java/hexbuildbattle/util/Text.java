package hexbuildbattle.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Map;

public final class Text {

    private static final LegacyComponentSerializer AMPERSAND =
            LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    public static Component component(String raw) {
        return AMPERSAND.deserialize(raw == null ? "" : raw);
    }

    public static String replace(String raw, Map<String, String> placeholders) {
        String output = raw == null ? "" : raw;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            output = output.replace("<" + entry.getKey() + ">", entry.getValue());
        }
        return output;
    }
}
