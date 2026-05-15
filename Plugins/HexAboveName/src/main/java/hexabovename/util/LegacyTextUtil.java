package hexabovename.util;

import org.bukkit.ChatColor;

public final class LegacyTextUtil {

    private LegacyTextUtil() {
    }

    public static String colorize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}
