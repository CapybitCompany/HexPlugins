package hex.minigames.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlayerOutputTextTest {
    @Test
    void playerFacingResourcesDoNotUseDnfLabel() throws IOException {
        Path root = Path.of("src", "main", "resources");
        if (!Files.isDirectory(root)) root = Path.of("Plugins", "HexMinigames", "src", "main", "resources");
        try (var files = Files.walk(root)) {
            List<String> offenders = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".yml"))
                    .filter(path -> containsUpperDnf(path))
                    .map(Path::toString)
                    .toList();
            assertTrue(offenders.isEmpty(), "Player-facing DNF label in " + offenders);
        }
    }

    private boolean containsUpperDnf(Path path) {
        try {
            return Files.readString(path).contains("DNF");
        } catch (IOException error) {
            throw new RuntimeException(error);
        }
    }
}
