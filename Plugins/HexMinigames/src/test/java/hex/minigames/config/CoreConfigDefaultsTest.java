package hex.minigames.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoreConfigDefaultsTest {
    @Test
    void productionAndDevelopmentMinimumsAreSeparate() {
        YamlConfiguration yaml = loadConfig();

        assertEquals(4, yaml.getInt("pregame.minimum-players"));
        assertEquals(2, yaml.getInt("development.minimum-players"));
    }

    @Test
    void globalMaxPlayersDefaultsToFourteen() {
        YamlConfiguration yaml = loadConfig();

        assertEquals(14, yaml.getInt("global.max-players"));
    }

    @Test
    void glassBridgeSpawnIsOneBlockHigherAndFacesNorth() {
        YamlConfiguration yaml = loadGame("glass_bridge.yml");

        Map<?, ?> spawn = yaml.getMapList("participant-spawns").get(0);
        assertEquals(27.0, ((Number) spawn.get("y")).doubleValue());
        assertEquals(180.0, ((Number) spawn.get("yaw")).doubleValue());
        assertEquals(27.0, yaml.getDouble("settings.respawn-spawn.y"));
        assertEquals(180.0, yaml.getDouble("settings.respawn-spawn.yaw"));
        assertEquals(-26, yaml.getInt("settings.fall-y"));
        assertEquals(10, yaml.getInt("settings.respawn-delay-seconds"));
        assertEquals(2, yaml.getInt("settings.actionbar-update-ticks"));
    }

    @Test
    void hotHeadTutorialAndArenaSpawnsAreOneBlockHigher() {
        YamlConfiguration yaml = loadGame("hot_head.yml");

        Map<?, ?> spawn = yaml.getMapList("participant-spawns").get(0);
        assertEquals(-31.0, ((Number) spawn.get("y")).doubleValue());
        assertEquals(-31.0, yaml.getDouble("settings.gameplay-spawn.y"));
    }

    @Test
    void popcornSpawnIsOneBlockAbovePlatform() {
        YamlConfiguration yaml = loadGame("popcorn.yml");

        Map<?, ?> spawn = yaml.getMapList("participant-spawns").get(0);
        assertEquals(595.0, ((Number) spawn.get("x")).doubleValue());
        assertEquals(-25.0, ((Number) spawn.get("y")).doubleValue());
        assertEquals(-128.0, ((Number) spawn.get("z")).doubleValue());
        assertTrue(((Number) spawn.get("y")).doubleValue() > yaml.getInt("settings.platform.pos1.y"));
    }

    @Test
    void redLightSpawnAndTimingsMatchLivePatch() {
        YamlConfiguration yaml = loadGame("red_light_green_light.yml");

        Map<?, ?> spawn = yaml.getMapList("participant-spawns").get(0);
        assertEquals(173.0, ((Number) spawn.get("x")).doubleValue());
        assertEquals(-30.0, ((Number) spawn.get("y")).doubleValue());
        assertEquals(-274.0, ((Number) spawn.get("z")).doubleValue());
        assertEquals(30, yaml.getInt("settings.lights.light-duration-min-ticks"));
        assertEquals(70, yaml.getInt("settings.lights.light-duration-max-ticks"));
        assertEquals(15, yaml.getInt("settings.lights.red-grace-ticks"));
    }

    @Test
    void implementedGamesUseTwentySecondTutorialRulesAndPinkWhiteBossbars() {
        for (String game : List.of("super_memory.yml", "red_light_green_light.yml", "hot_head.yml", "dalgona.yml", "glass_bridge.yml", "popcorn.yml", "tag.yml", "jump_rope.yml")) {
            YamlConfiguration yaml = loadGame(game);

            assertEquals(20, yaml.getInt("settings.tutorial.duration-seconds"), game);
            assertEquals(5, yaml.getInt("settings.tutorial.sound-from-seconds", yaml.getInt("settings.tutorial.sound-from-seconds", 5)), game);
            assertTrue(yaml.getStringList("settings.tutorial.chat-lines").size() >= 3, game);
            assertTrue(yaml.getString("settings.bossbar.title", "").startsWith("&d&l"), game);
            assertEquals("WHITE", yaml.getString("settings.bossbar.color"), game);
        }
    }

    private YamlConfiguration loadConfig() {
        return YamlConfiguration.loadConfiguration(resource("config.yml"));
    }

    @Test
    void roundDurationsMatchLatestBalance() {
        assertEquals(60, loadGame("hot_head.yml").getInt("round-time-seconds"));
        assertEquals(45, loadGame("popcorn.yml").getInt("round-time-seconds"));
        assertEquals(80, loadGame("dalgona.yml").getInt("round-time-seconds"));
        assertEquals(130, loadGame("glass_bridge.yml").getInt("round-time-seconds"));
    }

    @Test
    void existingSurvivalDurationsAreUpgradedWhenLoading() throws Exception {
        var loader = new MinigamesConfigLoader(null);
        var global = org.mockito.Mockito.mock(GlobalConfig.class);
        var load = MinigamesConfigLoader.class.getDeclaredMethod("loadGame", String.class, YamlConfiguration.class, GlobalConfig.class, List.class);
        load.setAccessible(true);
        for (String game : List.of("hot_head", "popcorn")) {
            var yaml = loadGame(game + ".yml");
            yaml.set("round-time-seconds", game.equals("hot_head") ? 120 : 90);
            var definition = (hex.minigames.game.MinigameDefinition) load.invoke(loader, game + ".yml", yaml, global, new java.util.ArrayList<String>());
            assertEquals(game.equals("hot_head") ? 60 : 45, definition.roundTimeSeconds());
        }
    }

    private YamlConfiguration loadGame(String fileName) {
        return YamlConfiguration.loadConfiguration(resource("games/" + fileName));
    }

    private File resource(String path) {
        Path modulePath = Path.of("src", "main", "resources", path);
        if (Files.isRegularFile(modulePath)) return modulePath.toFile();
        return Path.of("Plugins", "HexMinigames", "src", "main", "resources", path).toFile();
    }
}
