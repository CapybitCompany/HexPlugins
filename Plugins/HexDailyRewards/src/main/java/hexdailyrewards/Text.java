package hexdailyrewards;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    public static Component component(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }

    public static Component component(String text, Map<String, String> placeholders) {
        return component(apply(text, placeholders));
    }

    public static List<Component> lore(List<String> lines, Map<String, String> placeholders) {
        List<Component> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            out.add(component(line, placeholders));
        }
        return out;
    }

    public static String legacy(String text, Map<String, String> placeholders) {
        return ChatColor.translateAlternateColorCodes('&', apply(text, placeholders));
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

