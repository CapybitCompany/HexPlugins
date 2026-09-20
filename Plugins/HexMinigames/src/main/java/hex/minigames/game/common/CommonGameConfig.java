package hex.minigames.game.common;

import hex.minigames.config.ConfiguredSound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;

import java.util.List;

public final class CommonGameConfig {
    private CommonGameConfig() {
    }

    public static BossBarSettings bossBar(Object settings, String source, String defaultTitle, List<String> errors) {
        return new BossBarSettings(
                GameSettings.string(settings, "bossbar.title", defaultTitle),
                GameSettings.barColor(settings, "bossbar.color", BarColor.WHITE, source, errors),
                GameSettings.barStyle(settings, "bossbar.style", BarStyle.SOLID, source, errors)
        );
    }

    public static TutorialSettings tutorial(Object settings, String source, List<String> defaultLines, List<String> errors) {
        ConfiguredSound sound = GameSettings.sound(
                settings,
                "tutorial.countdown-sound",
                true,
                "UI_BUTTON_CLICK",
                0.8f,
                1.4f,
                source,
                errors
        );
        return new TutorialSettings(
                GameSettings.integer(settings, "tutorial.duration-seconds", 20),
                GameSettings.string(settings, "tutorial.title", "&6ZASADY"),
                GameSettings.string(settings, "tutorial.subtitle", "&f{seconds}"),
                GameSettings.integer(settings, "tutorial.title-stay-ticks", 25),
                sound,
                GameSettings.integer(settings, "tutorial.sound-from-seconds", 5),
                GameSettings.stringList(GameSettings.child(settings, "tutorial.chat-lines"), defaultLines)
        );
    }
}
