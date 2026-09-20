package hex.minigames.game.common;

import hex.minigames.config.ConfiguredSound;

import java.util.List;

public record TutorialSettings(
        int durationSeconds,
        String title,
        String subtitle,
        int titleStayTicks,
        ConfiguredSound countdownSound,
        int soundFromSeconds,
        List<String> chatLines
) {
    public TutorialSettings {
        durationSeconds = durationSeconds <= 0 ? 20 : durationSeconds;
        title = title == null || title.isBlank() ? "&6ZASADY" : title;
        subtitle = subtitle == null || subtitle.isBlank() ? "&f{seconds}" : subtitle;
        titleStayTicks = Math.max(1, titleStayTicks);
        countdownSound = countdownSound == null ? new ConfiguredSound(false, "", 1.0f, 1.0f) : countdownSound;
        soundFromSeconds = soundFromSeconds <= 0 ? 5 : soundFromSeconds;
        chatLines = chatLines == null ? List.of() : List.copyOf(chatLines);
    }
}
