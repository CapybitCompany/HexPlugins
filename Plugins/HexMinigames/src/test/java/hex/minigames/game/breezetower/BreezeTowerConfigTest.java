package hex.minigames.game.breezetower;

import hex.minigames.config.*;
import hex.minigames.game.MinigameDefinition;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class BreezeTowerConfigTest {
    @Test
    void bundledArenaLoadsWithExactCoordinatesAndIsAvailable() throws Exception {
        var yaml = YamlConfiguration.loadConfiguration(new File("src/main/resources/games/breeze_tower.yml"));
        var global = mock(GlobalConfig.class);
        when(global.worldName()).thenReturn("world");
        var method = MinigamesConfigLoader.class.getDeclaredMethod("loadGame", String.class, YamlConfiguration.class, GlobalConfig.class, List.class);
        method.setAccessible(true);
        List<String> errors = new ArrayList<>();
        var definition = (MinigameDefinition) method.invoke(new MinigamesConfigLoader(null), "breeze_tower.yml", yaml, global, errors);
        var config = BreezeTowerConfig.fromDefinition(definition, errors);
        assertTrue(errors.isEmpty(), errors.toString());
        assertTrue(definition.enabled());
        assertEquals(60, definition.roundTimeSeconds());
        assertEquals(-32, config.region().minX());
        assertEquals(117, config.region().maxX());
        assertEquals(-33, config.region().minY());
        assertEquals(47, config.region().maxY());
        assertEquals(-151, config.region().minZ());
        assertEquals(-7, config.region().maxZ());
        var tutorialSpawn = definition.participantSpawns().get(0);
        assertEquals(List.of(78.0, 9.0, -76.0), List.of(tutorialSpawn.x(), tutorialSpawn.y(), tutorialSpawn.z()));
        assertEquals(90.0f, tutorialSpawn.yaw());
        assertEquals(List.of(39.0, -2.0, -77.0), List.of(config.gameplaySpawn().x(), config.gameplaySpawn().y(), config.gameplaySpawn().z()));
        assertEquals(List.of(38.0, 8.0, -76.0), List.of(config.spectatorSpawn().x(), config.spectatorSpawn().y(), config.spectatorSpawn().z()));
        assertEquals(List.of(List.of(29.0, 2.0, -59.0), List.of(29.0, 1.0, -94.0),
                List.of(51.0, 2.0, -94.0), List.of(51.0, 3.0, -60.0)),
                config.breezes().stream().map(p -> List.of(p.x(), p.y(), p.z())).toList());
        assertEquals(-13, config.eliminationY());
        assertEquals(6, config.shotIntervalTicks());
    }
}
