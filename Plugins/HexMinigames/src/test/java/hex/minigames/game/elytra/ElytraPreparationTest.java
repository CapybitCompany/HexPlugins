package hex.minigames.game.elytra;
import hex.minigames.game.*;
import hex.minigames.model.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.*;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

final class ElytraPreparationTest {
    @TempDir Path directory;
    @Test void preparationClearsOnlyMasksAndCleanupRestoresMarkersAndGates() { prepareAndReset(true); }
    @Test void launchPlatesAtGatePositionsDoNotPreventRoundPreparation() { prepareAndReset(false); }
    private void prepareAndReset(boolean openableGates) {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Plugin plugin = mock(Plugin.class); when(plugin.getDataFolder()).thenReturn(directory.toFile());
            World world = mock(World.class); when(world.getName()).thenReturn("world");
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            var snapshot = mock(ChunkSnapshot.class);
            when(snapshot.getBlockType(anyInt(),anyInt(),anyInt())).thenReturn(Material.AIR);
            when(snapshot.getBlockType(6,-20,15)).thenReturn(Material.PINK_CONCRETE);
            when(snapshot.getBlockType(6,-21,15)).thenReturn(Material.YELLOW_WOOL);
            when(snapshot.getBlockType(6,-20,14)).thenReturn(Material.YELLOW_WOOL);
            var chunk = mock(Chunk.class);
            when(chunk.getChunkSnapshot(false,false,false)).thenReturn(snapshot);
            when(world.getChunkAt(anyInt(),anyInt())).thenReturn(chunk);
            Map<BlockPosition,Block> blocks = new HashMap<>();
            when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call -> blocks.computeIfAbsent(
                    new BlockPosition(call.getArgument(0),call.getArgument(1),call.getArgument(2)),p -> {
                        Block block = mock(Block.class);
                        boolean gate = p.x() < 278 && p.y() == -21 && p.z() == 63;
                        BlockData data = gate && openableGates ? mock(Openable.class) : mock(BlockData.class);
                        String encoded = p.toString();
                        when(data.getAsString()).thenReturn(encoded);
                        when(block.getBlockData()).thenReturn(data);
                        when(block.getType()).thenReturn(Material.AIR);
                        bukkit.when(() -> Bukkit.createBlockData(encoded)).thenReturn(data);
                        return block;
                    }));
            var definition = new MinigameDefinition("elytra","Elytra",true,true,false,1,14,1,
                    Optional.of(new CuboidRegion("world",new BlockPosition(276,-21,62),new BlockPosition(278,-19,63))),
                    List.of(new LocationSpec(276,-20,62,0,0,true)),Optional.empty(),90,
                    Map.of("ring-order",List.of(Map.of("x",278,"y",-20,"z",63))),"test");
            RoundContext context = mock(RoundContext.class);
            when(context.definition()).thenReturn(definition); when(context.onlineParticipants()).thenReturn(List.of());
            var game = new ElytraMinigame(plugin);
            assertTrue(game.availability(definition,1).available());
            game.prepare(context);
            for (BlockPosition p : List.of(new BlockPosition(278,-20,63),new BlockPosition(278,-21,63),new BlockPosition(278,-20,62))) {
                verify(blocks.get(p)).setType(Material.AIR,false);
            }
            game.reset(context);
            for (Block block : blocks.values()) {
                // Decorative neighboring cells are never captured or modified.
                if (mockingDetails(block).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("setType"))) {
                    BlockData original = block.getBlockData();
                    verify(block).setBlockData(original,false);
                }
            }
            assertFalse(java.nio.file.Files.exists(directory.resolve("elytra-marker-recovery.yml")));
        }
    }
}
