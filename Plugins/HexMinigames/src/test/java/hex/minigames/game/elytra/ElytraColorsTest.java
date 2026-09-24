package hex.minigames.game.elytra;

import hex.minigames.model.BlockPosition;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;

final class ElytraColorsTest {
    @Test void distantPassedFramesAreReappliedAfterChunkReload() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Plugin plugin = mock(Plugin.class); World world = mock(World.class); Player player = mock(Player.class);
            UUID id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id); when(player.getWorld()).thenReturn(world);
            when(player.isOnline()).thenReturn(true); when(player.getLocation()).thenReturn(new Location(world,0,60,0));
            var green = mock(BlockData.class); bukkit.when(() -> Bukkit.createBlockData(Material.LIME_CONCRETE)).thenReturn(green);
            var scheduler = mock(BukkitScheduler.class); bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            List<Runnable> queued = new ArrayList<>();
            when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call -> { queued.add(call.getArgument(1)); return null; });
            ElytraMinigame game = new ElytraMinigame(plugin);
            var worldField = ElytraMinigame.class.getDeclaredField("world"); worldField.setAccessible(true); worldField.set(game,world);
            var colors = ElytraMinigame.class.getDeclaredField("colored"); colors.setAccessible(true);
            @SuppressWarnings("unchecked") Map<UUID,Set<BlockPosition>> colored = (Map<UUID,Set<BlockPosition>>)colors.get(game);
            colored.put(id,Set.of(new BlockPosition(320,60,320)));
            var refresh = ElytraMinigame.class.getDeclaredMethod("showFrames",Player.class); refresh.setAccessible(true); refresh.invoke(game,player);
            verify(player).sendBlockChange(new Location(world,320,60,320),green);
            clearInvocations(player);
            game.onChunkLoad(null,player,20,20); queued.forEach(Runnable::run);
            verify(player).sendBlockChange(new Location(world,320,60,320),green);
        }
    }
}
