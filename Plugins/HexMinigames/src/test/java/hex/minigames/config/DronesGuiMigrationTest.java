package hex.minigames.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DronesGuiMigrationTest {
    @TempDir Path folder;
    @Test void installedDefaultChangesOnceWithBackup() throws Exception { check(12,32); }
    @Test void customTimingIsPreserved() throws Exception { check(45,45); }
    private void check(int before,int after) throws Exception {
        Path file=folder.resolve("games/drones.yml"); Files.createDirectories(file.getParent());
        String original="settings:\n  puzzles:\n    sequence:\n      green-show-ticks: "+before+"\n";
        Files.writeString(file,original);
        Plugin plugin=mock(Plugin.class); when(plugin.getDataFolder()).thenReturn(folder.toFile());
        var loader=new MinigamesConfigLoader(plugin);
        var method=MinigamesConfigLoader.class.getDeclaredMethod("migrateDronesGui"); method.setAccessible(true);
        method.invoke(loader); method.invoke(loader);
        var yaml=YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(after,yaml.getInt("settings.puzzles.sequence.green-show-ticks")); assertEquals(1,yaml.getInt("gui-revision"));
        assertEquals(original,Files.readString(file.resolveSibling("drones.yml.before-gui-1.bak")));
    }
}
