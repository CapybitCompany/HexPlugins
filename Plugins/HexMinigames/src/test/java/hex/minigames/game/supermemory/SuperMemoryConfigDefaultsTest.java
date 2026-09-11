package hex.minigames.game.supermemory;

import hex.minigames.game.MinigameDefinition;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SuperMemoryConfigDefaultsTest {
    @Test
    void defaultSuperMemoryConfigIsValid() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(resource("games/super_memory.yml"));
        MinigameDefinition definition = definition(yaml);
        List<String> errors = new ArrayList<>();

        SuperMemoryConfig config = SuperMemoryConfig.fromDefinition(definition, errors);

        assertTrue(errors.isEmpty(), String.join("\n", errors));
        assertEquals(14, config.stations().size());
        for (SuperMemoryConfig.StationConfig station : config.stations()) {
            assertEquals(8, station.clickBlocks().size());
        }
    }

    private MinigameDefinition definition(YamlConfiguration yaml) {
        return new MinigameDefinition(
                yaml.getString("id"),
                yaml.getString("display-name"),
                yaml.getBoolean("enabled"),
                true,
                false,
                yaml.getInt("min-players"),
                yaml.getInt("max-players"),
                yaml.getInt("weight"),
                Optional.of(region(yaml.getConfigurationSection("region"))),
                List.of(new LocationSpec(0, 0, 0, 0, 0, true)),
                Optional.of(new LocationSpec(0, 0, 0, 0, 0, true)),
                yaml.getInt("round-time-seconds"),
                sectionMap(yaml.getConfigurationSection("settings")),
                "games/super_memory.yml"
        );
    }

    private CuboidRegion region(ConfigurationSection section) {
        return new CuboidRegion("Hex_Minigames", block(section.getConfigurationSection("pos1")), block(section.getConfigurationSection("pos2")));
    }

    private BlockPosition block(ConfigurationSection section) {
        return new BlockPosition(section.getInt("x"), section.getInt("y"), section.getInt("z"));
    }

    private Map<String, Object> sectionMap(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            out.put(key, section.get(key));
        }
        return out;
    }

    private File resource(String path) {
        Path modulePath = Path.of("src", "main", "resources", path);
        if (Files.isRegularFile(modulePath)) return modulePath.toFile();
        return Path.of("Plugins", "HexMinigames", "src", "main", "resources", path).toFile();
    }
}
