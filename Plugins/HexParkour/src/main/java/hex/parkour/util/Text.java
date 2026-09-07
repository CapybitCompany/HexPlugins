package hex.parkour.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.util.List;

public final class Text {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    public static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public static List<String> color(List<String> lines) {
        if (lines == null || lines.isEmpty()) return List.of();
        return lines.stream().map(Text::color).toList();
    }

    public static Component component(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }
}
