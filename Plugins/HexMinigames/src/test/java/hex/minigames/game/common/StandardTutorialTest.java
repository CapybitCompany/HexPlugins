package hex.minigames.game.common;

import hex.minigames.config.ConfiguredSound;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StandardTutorialTest {
    @Test
    void countdownSoundOnlyPlaysFromFiveToOne() {
        TutorialSettings settings = new TutorialSettings(20, "&6ZASADY", "&f{seconds}", 25, new ConfiguredSound(true, "UI_BUTTON_CLICK", 1.0f, 1.0f), 5, List.of());

        for (int second = 20; second >= 6; second--) {
            assertFalse(StandardTutorial.shouldPlayCountdownSound(second, settings));
        }
        for (int second = 5; second >= 1; second--) {
            assertTrue(StandardTutorial.shouldPlayCountdownSound(second, settings));
        }
        assertFalse(StandardTutorial.shouldPlayCountdownSound(0, settings));
    }

    @Test
    void tutorialDefaultsToTwentySecondsAndFiveSecondSoundWindow() {
        TutorialSettings settings = new TutorialSettings(0, null, null, 0, null, 0, List.of("&d&lTEST"));

        assertEquals(20, settings.durationSeconds());
        assertEquals(5, settings.soundFromSeconds());
        assertEquals(List.of("&d&lTEST"), settings.chatLines());
    }

    @Test
    void sourceDoesNotDispatchHexChatMute() throws IOException {
        Path root = Path.of("src", "main", "java");
        if (!Files.isDirectory(root)) root = Path.of("Plugins", "HexMinigames", "src", "main", "java");
        try (var files = Files.walk(root)) {
            List<String> offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "hexchat mute") || contains(path, "hexchat unmute"))
                    .map(Path::toString)
                    .toList();
            assertTrue(offenders.isEmpty(), "Forbidden HexChat mute dispatch in " + offenders);
        }
    }

    private boolean contains(Path path, String needle) {
        try {
            return Files.readString(path).toLowerCase(java.util.Locale.ROOT).contains(needle);
        } catch (IOException error) {
            throw new RuntimeException(error);
        }
    }
}
