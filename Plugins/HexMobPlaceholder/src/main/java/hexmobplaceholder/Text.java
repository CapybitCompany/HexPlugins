package hexmobplaceholder;

import org.bukkit.ChatColor;

import java.util.Map;

public final class Text {

    private Text() {
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public static String color(String text, Map<String, String> values) {
        String out = text == null ? "" : text;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return color(out);
    }
}
