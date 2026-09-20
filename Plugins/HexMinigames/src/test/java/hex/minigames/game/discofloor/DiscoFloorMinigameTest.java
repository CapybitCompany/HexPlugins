package hex.minigames.game.discofloor;

import hex.minigames.game.*;
import hex.minigames.game.common.RespawnEffects;
import hex.minigames.model.*;
import hex.minigames.runtime.RoundPlayerState;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

final class DiscoFloorMinigameTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<RespawnEffects> effects;
    private final UUID id = UUID.randomUUID();
    private final Map<String, Block> blocks = new HashMap<>();
    private final List<AtomicReference<Material>> materials = new ArrayList<>();
    private World world;
    private Player player;
    private RoundContext context;
    private DiscoFloorMinigame game;
    private long tick;
    private RoundPlayerState state = RoundPlayerState.ACTIVE;

    @BeforeEach void setup() {
        bukkit = mockStatic(Bukkit.class);
        effects = mockStatic(RespawnEffects.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        bukkit.when(() -> Bukkit.createBossBar(anyString(), any(BarColor.class), any(BarStyle.class))).thenReturn(mock(BossBar.class));
        when(world.getBlockAt(anyInt(), eq(-32), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0), z = call.getArgument(2);
            return blocks.computeIfAbsent(x + ":" + z, key -> {
                var material = new AtomicReference<>(x % 2 == 0 ? Material.RED_CONCRETE : Material.PINK_CONCRETE);
                materials.add(material);
                var block = mock(Block.class);
                var original = mock(BlockData.class);
                when(original.getMaterial()).thenReturn(material.get());
                when(block.getType()).thenAnswer(ignored -> material.get());
                when(block.getBlockData()).thenReturn(original);
                doAnswer(change -> { material.set(change.getArgument(0)); return null; }).when(block).setType(any(Material.class), eq(false));
                doAnswer(change -> { material.set(change.<BlockData>getArgument(0).getMaterial()); return null; }).when(block).setBlockData(any(BlockData.class), eq(false));
                return block;
            });
        });
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(call -> new Location(world, 570, -31, -10));
        context = mock(RoundContext.class);
        when(context.participants()).thenReturn(Set.of(id));
        when(context.onlineParticipants()).thenReturn(List.of(player));
        when(context.state(id)).thenAnswer(call -> state);
        doAnswer(call -> { state = call.getArgument(1); return null; }).when(context).state(eq(id), any());
        when(context.elapsedTicks()).thenAnswer(call -> tick);
        when(context.definition()).thenReturn(new MinigameDefinition("disco_floor", "Disco", true, true, false,
                1, 14, 1, Optional.of(new CuboidRegion("world", new BlockPosition(549, -37, -30), new BlockPosition(590, -4, 11))),
                List.of(new LocationSpec(570, -31, -10, 0, 0, true)), Optional.empty(), 77, Map.of(), "test"));
        game = new DiscoFloorMinigame(mock(Plugin.class));
        game.prepare(context);
    }
    @AfterEach void cleanup() { effects.close(); bukkit.close(); }

    @Test void fullCycleKeepsColorFiveSecondsRestoresFloorAndAwardsFourPointsAfterSevenRounds() {
        game.start(context);
        tick = 60; game.handleTick(context);
        verify(player).sendTitle(eq(""), contains("3"), eq(0), eq(20), eq(0));
        assertEquals(0, airCount());
        tick = 119; game.handleTick(context);
        assertEquals(0, airCount());
        tick = 120; game.handleTick(context);
        assertEquals(648, airCount());
        tick = 219; game.handleTick(context);
        assertEquals(648, airCount());
        tick = 220; game.handleTick(context);
        assertEquals(0, airCount());
        for (tick = 240; tick <= 1540; tick += 20) game.handleTick(context);
        verify(context).requestFinish(RoundEndReason.MINIGAME_REQUEST);
        assertEquals(0, airCount());
        var result = game.finish(context, RoundEndReason.MINIGAME_REQUEST);
        assertEquals(4, result.players().get(id).points());
        assertTrue(result.players().get(id).completed());
        game.reset(context);
        assertEquals(0, airCount());
    }
    @Test void fallingAfterTwoRoundsExplodesOnceAndGetsOnePoint() {
        game.start(context);
        for (tick = 20; tick <= 440; tick += 20) game.handleTick(context);
        var fall = new PlayerMoveEvent(player, player.getLocation(), new Location(world, 570, -47, -10));
        game.onMove(context, fall);
        game.onMove(context, fall);
        effects.verify(() -> RespawnEffects.explosion(player), times(1));
        verify(player).setGameMode(GameMode.SPECTATOR);
        verify(context).respawn(eq(player), eq(new Location(world, 577, -26, -3)), isNull());
        for (tick = 460; tick <= 1540; tick += 20) game.handleTick(context);
        assertEquals(1, game.finish(context, RoundEndReason.TIME_LIMIT).players().get(id).points());
    }
    private long airCount() { return materials.stream().filter(value -> value.get() == Material.AIR).count(); }
}
