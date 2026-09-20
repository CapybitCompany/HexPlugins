package hex.minigames.game.common;

import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;

public record BossBarSettings(String title, BarColor color, BarStyle style) {
    public BossBarSettings {
        title = title == null || title.isBlank() ? "&dMINIGRA" : title;
        color = color == null ? BarColor.WHITE : color;
        style = style == null ? BarStyle.SOLID : style;
    }
}
