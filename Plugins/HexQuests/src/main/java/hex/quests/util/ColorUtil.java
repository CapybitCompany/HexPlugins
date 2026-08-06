package hex.quests.util;

import org.bukkit.ChatColor;

public final class ColorUtil {
    private ColorUtil() {}

    public static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }
}
