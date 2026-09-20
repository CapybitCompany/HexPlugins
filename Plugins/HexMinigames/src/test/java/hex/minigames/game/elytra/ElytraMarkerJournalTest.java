package hex.minigames.game.elytra;
import hex.minigames.model.BlockPosition;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

final class ElytraMarkerJournalTest {
    @TempDir Path folder;
    @Test void crashRecoveryRestoresExactMarkersAndRetainsSnapshotIfWorldUnavailable() {
        Plugin plugin = mock(Plugin.class); when(plugin.getDataFolder()).thenReturn(folder.toFile());
        World world = mock(World.class); when(world.getName()).thenReturn("arena");
        Block pink = mock(Block.class), wool = mock(Block.class);
        BlockData pinkData = mock(BlockData.class), woolData = mock(BlockData.class);
        when(pinkData.getAsString()).thenReturn("minecraft:pink_concrete");
        when(woolData.getAsString()).thenReturn("minecraft:yellow_wool");
        when(pink.getBlockData()).thenReturn(pinkData); when(wool.getBlockData()).thenReturn(woolData);
        when(world.getBlockAt(1,2,3)).thenReturn(pink); when(world.getBlockAt(1,2,4)).thenReturn(wool);
        ElytraMarkerJournal.save(plugin,world,Set.of(new BlockPosition(1,2,3),new BlockPosition(1,2,4)));
        assertTrue(Files.exists(folder.resolve("elytra-marker-recovery.yml")));
        try (var bukkit = mockStatic(Bukkit.class)) {
            assertThrows(IllegalStateException.class,() -> ElytraMarkerJournal.recover(plugin));
            assertTrue(Files.exists(folder.resolve("elytra-marker-recovery.yml")));
            bukkit.when(() -> Bukkit.getWorld("arena")).thenReturn(world);
            bukkit.when(() -> Bukkit.createBlockData("minecraft:pink_concrete")).thenReturn(pinkData);
            bukkit.when(() -> Bukkit.createBlockData("minecraft:yellow_wool")).thenReturn(woolData);
            ElytraMarkerJournal.recover(plugin);
            verify(pink).setBlockData(pinkData,false); verify(wool).setBlockData(woolData,false);
            assertFalse(Files.exists(folder.resolve("elytra-marker-recovery.yml")));
        }
    }
}
