package hex.minigames.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CoreConfigDefaultsTest {
    @Test
    void productionAndDevelopmentMinimumsAreSeparate() {
        YamlConfiguration yaml = loadConfig();

        assertEquals(5, yaml.getInt("pregame.minimum-players"));
        assertEquals(2, yaml.getInt("development.minimum-players"));
    }

    @Test
    void globalMaxPlayersDefaultsToFourteen() {
        YamlConfiguration yaml = loadConfig();

        assertEquals(14, yaml.getInt("global.max-players"));
    }

    private YamlConfiguration loadConfig() {
        return YamlConfiguration.loadConfiguration(resource("config.yml"));
    }

    private File resource(String path) {
        Path modulePath = Path.of("src", "main", "resources", path);
        if (Files.isRegularFile(modulePath)) return modulePath.toFile();
        return Path.of("Plugins", "HexMinigames", "src", "main", "resources", path).toFile();
    }
}
