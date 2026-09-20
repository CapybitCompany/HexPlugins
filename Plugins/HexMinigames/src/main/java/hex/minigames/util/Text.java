package hex.minigames.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.time.Duration;

public final class Text {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    public static String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    public static Component component(String input) {
        return LEGACY.deserialize(input == null ? "" : input);
    }

    public static void showTitle(Player player, String title, String subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        if (player == null) return;
        player.showTitle(Title.title(
                component(title),
                component(subtitle),
                Title.Times.times(ticks(fadeInTicks), ticks(stayTicks), ticks(fadeOutTicks))
        ));
    }

    public static void clearTitle(Player player) {
        if (player != null) player.clearTitle();
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(Math.max(0L, ticks) * 50L);
    }
}
