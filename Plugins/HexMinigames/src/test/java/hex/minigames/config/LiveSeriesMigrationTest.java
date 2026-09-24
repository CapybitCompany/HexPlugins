package hex.minigames.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class LiveSeriesMigrationTest {
    @TempDir Path directory;
    @Test void existingBombKnockbackIsReducedOnceWithBackup() throws Exception {
        Files.createDirectories(directory.resolve("games"));
        Path file=directory.resolve("games/monkey_run.yml");
        Files.writeString(file,"settings:\n  bombs:\n    knockback: 2.5\n");
        Plugin plugin=mock(Plugin.class); when(plugin.getDataFolder()).thenReturn(directory.toFile());
        var loader=new MinigamesConfigLoader(plugin);
        var migrate=MinigamesConfigLoader.class.getDeclaredMethod("migrateBombKnockback"); migrate.setAccessible(true); migrate.invoke(loader);
        var yaml=YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(1.8,yaml.getDouble("settings.bombs.knockback"));
        assertTrue(Files.exists(file.resolveSibling("monkey_run.yml.before-bomb-knockback-1.bak")));
        yaml.set("settings.bombs.knockback",2.0); yaml.save(file.toFile()); migrate.invoke(loader);
        assertEquals(2.0,YamlConfiguration.loadConfiguration(file.toFile()).getDouble("settings.bombs.knockback"));
    }
    @Test void upgradesExistingFilesOnceWithBackupsAndPreservesArenaCoordinates() throws Exception {
        Files.createDirectories(directory.resolve("games"));
        Files.writeString(directory.resolve("config.yml"), "countdowns:\n  round: 5\ndurations:\n  round-results-seconds: 10\n  intermission-seconds: 8\n  series-results-seconds: 17\n");
        Files.writeString(directory.resolve("messages.yml"), "prefix: Custom\n");
        Files.writeString(directory.resolve("games/popcorn.yml"), "round-time-seconds: 45\nsettings:\n  platform:\n    pos1: { x: 600, y: -26, z: -110 }\n");
        Files.writeString(directory.resolve("games/monkey_run.yml"), "settings:\n  bombs:\n    max-interval-seconds: 40\n");
        Plugin plugin = mock(Plugin.class); when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getResource("games/monkey_run.yml")).thenAnswer(call -> getClass().getClassLoader().getResourceAsStream("games/monkey_run.yml"));
        var loader = new MinigamesConfigLoader(plugin);
        var migrate = MinigamesConfigLoader.class.getDeclaredMethod("migrateLiveSeriesDefaults"); migrate.setAccessible(true); migrate.invoke(loader);
        var main = YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals(7, main.getInt("countdowns.round") + main.getInt("durations.round-results-seconds") + main.getInt("durations.intermission-seconds"));
        assertEquals(17,main.getInt("durations.series-results-seconds"));
        var popcorn = YamlConfiguration.loadConfiguration(directory.resolve("games/popcorn.yml").toFile());
        assertEquals(35,popcorn.getInt("round-time-seconds")); assertEquals(600,popcorn.getInt("settings.platform.pos1.x"));
        assertEquals(12,popcorn.getInt("settings.hazard.target-remaining-blocks"));
        var sumo = YamlConfiguration.loadConfiguration(directory.resolve("games/monkey_run.yml").toFile());
        assertEquals(40,sumo.getInt("settings.bombs.max-interval-seconds")); assertEquals(8,sumo.getInt("settings.bombs.min-interval-seconds"));
        assertTrue(Files.exists(directory.resolve("config.yml.before-live-series-1.bak")));
        main.set("countdowns.round",9); main.save(directory.resolve("config.yml").toFile());
        migrate.invoke(loader);
        assertEquals(9,YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile()).getInt("countdowns.round"));
    }
}
