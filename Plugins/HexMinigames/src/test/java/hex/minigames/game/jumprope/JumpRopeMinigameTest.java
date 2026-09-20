package hex.minigames.game.jumprope;

import hex.minigames.game.*;
import hex.minigames.game.common.RespawnEffects;
import hex.minigames.model.*;
import hex.minigames.runtime.RoundPlayerState;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.*;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

final class JumpRopeMinigameTest {
    @Test
    void boundaryRespawnDelayFinishAndRopeRestorationWorkTogether() {
        try (var bukkit = mockStatic(Bukkit.class); var effects = mockStatic(RespawnEffects.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(() -> Bukkit.createBossBar(anyString(), any(BarColor.class), any(BarStyle.class))).thenReturn(mock(BossBar.class));
            BlockData original = mock(BlockData.class);
            bukkit.when(() -> Bukkit.createBlockData(Material.BLACK_STAINED_GLASS)).thenReturn(original);
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(Material.BLACK_STAINED_GLASS);
            when(block.getBlockData()).thenReturn(original);
            when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
            List<BlockDisplay> displays = new ArrayList<>();
            when(world.spawn(any(Location.class), eq(BlockDisplay.class), org.mockito.ArgumentMatchers.<Consumer<BlockDisplay>>any()))
                    .thenAnswer(call -> {
                        var display = mock(BlockDisplay.class);
                        when(display.isValid()).thenReturn(true);
                        call.<Consumer<BlockDisplay>>getArgument(2).accept(display);
                        displays.add(display);
                        return display;
                    });
            Player player = mock(Player.class);
            UUID id = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(id);
            when(player.getVelocity()).thenReturn(new Vector(0, -0.2, 0));
            when(player.getLocation()).thenAnswer(call -> new Location(world, 450, 12, -52));
            when(player.getBoundingBox()).thenReturn(new BoundingBox(449.7, 12, -52.3, 450.3, 13.8, -51.7));
            var context = mock(RoundContext.class);
            when(context.onlineParticipants()).thenReturn(List.of(player));
            when(context.participants()).thenReturn(Set.of(id));
            when(context.definition()).thenReturn(definition());
            AtomicReference<RoundPlayerState> state = new AtomicReference<>(RoundPlayerState.ACTIVE);
            AtomicReference<Runnable> arrival = new AtomicReference<>();
            AtomicLong tick = new AtomicLong();
            when(context.state(id)).thenAnswer(call -> state.get());
            doAnswer(call -> { state.set(call.getArgument(1)); return null; }).when(context).state(eq(id), any());
            when(context.elapsedTicks()).thenAnswer(call -> tick.get());
            doAnswer(call -> { arrival.set(call.getArgument(2)); return null; }).when(context).respawn(eq(player), any(Location.class), any(Runnable.class));
            var game = new JumpRopeMinigame(mock(Plugin.class));
            game.prepare(context);
            assertEquals(36, displays.size());
            var cross = new PlayerMoveEvent(player, new Location(world, 450, 12, -55.8), new Location(world, 450, 11.8, -56.2));
            assertEquals(EventDecision.PASS, game.onMove(context, cross));
            assertEquals(-55.6, cross.getTo().getZ());
            assertEquals(11.8, cross.getTo().getY());
            game.start(context);
            tick.set(50);
            var fall = new PlayerMoveEvent(player, player.getLocation(), new Location(world, 450, -23, -65));
            game.onMove(context, fall);
            assertEquals(RoundPlayerState.RESPAWN_DELAY, state.get());
            verify(context).respawn(eq(player), eq(new Location(world, 450, 12, -52, 180, 0)), any(Runnable.class));
            effects.verify(() -> RespawnEffects.explosion(player), times(1));
            arrival.get().run();
            tick.set(249);
            game.handleTick(context);
            assertEquals(RoundPlayerState.RESPAWN_DELAY, state.get());
            tick.set(250);
            game.handleTick(context);
            assertEquals(RoundPlayerState.ACTIVE, state.get());
            var finish = new PlayerMoveEvent(player, new Location(world, 450, 13, -82.8), new Location(world, 450, 13, -83.2));
            game.onMove(context, finish);
            assertEquals(RoundPlayerState.FINISHED, state.get());
            verify(context).requestFinish(RoundEndReason.MINIGAME_REQUEST);
            var result = game.finish(context, RoundEndReason.MINIGAME_REQUEST);
            assertEquals(2, result.players().get(id).points());
            game.reset(context);
            for (var display : displays) verify(display).remove();
            verify(block, times(36)).setBlockData(original, false);
        }
    }

    @Test
    void finishRequiresForwardCrossingWithinWidthAndAboveDeck() {
        var config = JumpRopeConfig.fromDefinition(definition(), new ArrayList<>());
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        assertTrue(JumpRopeMinigame.crossedFinish(config, new Location(world, 450, 14.2, -82), new Location(world, 450, 14.2, -84)));
        assertFalse(JumpRopeMinigame.crossedFinish(config, new Location(world, 450, 13, -84), new Location(world, 450, 13, -82)));
        assertFalse(JumpRopeMinigame.crossedFinish(config, new Location(world, 460, 13, -82), new Location(world, 460, 13, -84)));
        assertFalse(JumpRopeMinigame.crossedFinish(config, new Location(world, 450, 10, -82), new Location(world, 450, 10, -84)));
    }

    private MinigameDefinition definition() {
        return new MinigameDefinition("jump_rope", "Rope", true, true, false, 1, 14, 1,
                Optional.of(new CuboidRegion("world", new BlockPosition(414, -33, -94), new BlockPosition(486, 33, -45))),
                List.of(new LocationSpec(450, 12, -52, 180, 0, true)), Optional.empty(), 90, Map.of(), "test");
    }
}
