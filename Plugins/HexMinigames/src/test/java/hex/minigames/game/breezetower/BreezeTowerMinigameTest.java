package hex.minigames.game.breezetower;

import hex.minigames.game.*;
import hex.minigames.game.common.RespawnEffects;
import hex.minigames.model.*;
import hex.minigames.runtime.RoundPlayerState;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

final class BreezeTowerMinigameTest {
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<RespawnEffects> effects;
    private World world;
    private Player player;
    private RoundContext context;
    private BreezeTowerMinigame game;
    private final UUID id = UUID.randomUUID();
    private final List<Breeze> npcs = new ArrayList<>();
    private final List<BreezeWindCharge> projectiles = new ArrayList<>();
    private RoundPlayerState state;
    private long tick;

    @BeforeEach
    void setup() {
        bukkit = mockStatic(Bukkit.class);
        effects = mockStatic(RespawnEffects.class);
        world = mock(World.class);
        when(world.addPluginChunkTicket(anyInt(), anyInt(), any(Plugin.class))).thenReturn(true);
        when(world.getChunkAt(anyInt(), anyInt())).thenReturn(mock(Chunk.class));
        when(world.getName()).thenReturn("world");
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        bukkit.when(() -> Bukkit.createBossBar(anyString(), any(BarColor.class), any(BarStyle.class)))
                .thenReturn(mock(BossBar.class));
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenAnswer(call -> new Location(world, 38, 3, -76));
        context = mock(RoundContext.class);
        state = RoundPlayerState.ACTIVE;
        when(context.definition()).thenReturn(definition());
        when(context.participants()).thenReturn(Set.of(id));
        when(context.onlineParticipants()).thenReturn(List.of(player));
        when(context.player(id)).thenReturn(Optional.of(player));
        when(context.state(id)).thenAnswer(call -> state);
        doAnswer(call -> { state = call.getArgument(1); return null; }).when(context).state(eq(id), any());
        when(context.elapsedTicks()).thenAnswer(call -> tick);
        when(world.spawn(any(Location.class), eq(Breeze.class), org.mockito.ArgumentMatchers.<Consumer<Breeze>>any())).thenAnswer(call -> {
            var npc = mock(Breeze.class);
            Location spawn = call.getArgument(0);
            when(npc.getWorld()).thenReturn(world);
            when(npc.getEyeLocation()).thenAnswer(ignored -> spawn.clone().add(0, 1.4, 0));
            when(npc.isValid()).thenReturn(true);
            when(npc.hasLineOfSight(player)).thenReturn(true);
            call.<Consumer<Breeze>>getArgument(2).accept(npc);
            npcs.add(npc);
            return npc;
        });
        when(world.spawn(any(Location.class), eq(BreezeWindCharge.class), org.mockito.ArgumentMatchers.<Consumer<BreezeWindCharge>>any())).thenAnswer(call -> {
            var charge = mock(BreezeWindCharge.class);
            Location spawn = call.getArgument(0);
            when(charge.getUniqueId()).thenReturn(UUID.randomUUID());
            when(charge.getLocation()).thenAnswer(ignored -> spawn.clone());
            when(charge.isValid()).thenReturn(true);
            call.<Consumer<BreezeWindCharge>>getArgument(2).accept(charge);
            projectiles.add(charge);
            return charge;
        });
        Plugin plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
        game = new BreezeTowerMinigame(plugin);
        game.prepare(context);
    }

    @AfterEach
    void cleanup() {
        effects.close();
        bukkit.close();
    }

    @Test
    void npcsFloatWithoutAiAndShotsStartOnlyAfterTutorialFromNpcEyes() {
        assertEquals(4, npcs.size());
        for (var npc : npcs) {
            verify(npc).setAI(false);
            verify(npc).setGravity(false);
            verify(npc).setInvulnerable(true);
            verify(npc).setCollidable(false);
            verify(npc).setDespawnInPeacefulOverride(net.kyori.adventure.util.TriState.FALSE);
        }
        game.handleTick(context);
        assertTrue(projectiles.isEmpty());
        game.start(context);
        verify(player).teleport(new Location(world, 39, -2, -77, 135, 0));
        tick = 40;
        game.handleTick(context);
        assertTrue(projectiles.size() >= 1 && projectiles.size() <= 2);
        int firstVolley = projectiles.size();
        verify(projectiles.get(0)).setShooter(npcs.get(0));
        verify(world).spawn(eq(npcs.get(0).getEyeLocation()), eq(BreezeWindCharge.class), org.mockito.ArgumentMatchers.<Consumer<BreezeWindCharge>>any());
        tick = 45;
        game.handleTick(context);
        assertEquals(firstVolley, projectiles.size());
        tick = 46;
        game.handleTick(context);
        assertTrue(projectiles.size() > firstVolley);
        verify(projectiles.get(firstVolley)).setShooter(npcs.get(firstVolley));
        game.reset(context);
        verify(world, times(9)).removePluginChunkTicket(anyInt(), anyInt(), any(Plugin.class));
        for (var npc : npcs) verify(npc).remove();
        for (var charge : projectiles) verify(charge).remove();
    }

    @Test
    void fallAtMinusThirteenExplodesOnceAndSendsSpectatorToExactSpawnWithEarnedPoints() {
        game.start(context);
        tick = 800;
        var above = new PlayerMoveEvent(player, player.getLocation(), new Location(world, 38, -12.99, -76));
        game.onMove(context, above);
        assertEquals(RoundPlayerState.ACTIVE, state);
        var fall = new PlayerMoveEvent(player, above.getTo(), new Location(world, 38, -13, -76));
        game.onMove(context, fall);
        game.onMove(context, fall);
        effects.verify(() -> RespawnEffects.explosion(player), times(1));
        assertEquals(RoundPlayerState.GHOST, state);
        verify(player).setGameMode(GameMode.SPECTATOR);
        verify(context).respawn(eq(player), eq(new Location(world, 38, 8, -76, 0, 30)), isNull());
        tick = 1200;
        var result = game.finish(context, RoundEndReason.TIME_LIMIT);
        assertEquals(2, result.players().get(id).points());
        assertFalse(result.players().get(id).completed());
        assertSame(result, game.finish(context, RoundEndReason.TIME_LIMIT));
    }

    @Test
    void ownedWindChargeKeepsKnockbackWithoutHealthDamageButPvpIsDenied() {
        game.start(context);
        tick = 40;
        game.handleTick(context);
        var hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(player);
        when(hit.getDamager()).thenReturn(projectiles.get(0));
        assertEquals(EventDecision.ALLOW, game.onDamage(context, hit));
        verify(hit).setDamage(0);
        when(hit.getDamager()).thenReturn(mock(Player.class));
        assertEquals(EventDecision.DENY, game.onDamage(context, hit));
        when(hit.getDamager()).thenReturn(mock(BreezeWindCharge.class));
        assertEquals(EventDecision.DENY, game.onDamage(context, hit));
        var explosion = mock(EntityExplodeEvent.class);
        when(explosion.getEntity()).thenReturn(projectiles.get(0));
        var blocks = new ArrayList<org.bukkit.block.Block>(List.of(mock(org.bukkit.block.Block.class)));
        when(explosion.blockList()).thenReturn(blocks);
        game.onEntityExplode(context, explosion);
        assertTrue(blocks.isEmpty());
    }

    @Test
    void survivorGetsThreePointsAndTimedOutProjectilesAreRemoved() {
        game.start(context);
        tick = 40;
        game.handleTick(context);
        tick = 140;
        game.handleTick(context);
        verify(projectiles.get(0)).remove();
        tick = 1200;
        var result = game.finish(context, RoundEndReason.TIME_LIMIT);
        assertEquals(3, result.players().get(id).points());
        assertTrue(result.players().get(id).completed());
        for (var npc : npcs) verify(npc).remove();
    }

    @Test
    void cancelledNpcSpawnFailsClearlyAndCleansUpEntities() {
        Breeze rejected = mock(Breeze.class);
        when(world.spawn(any(Location.class), eq(Breeze.class),
                org.mockito.ArgumentMatchers.<Consumer<Breeze>>any())).thenAnswer(call -> {
            call.<Consumer<Breeze>>getArgument(2).accept(rejected);
            return rejected;
        });
        var error = assertThrows(IllegalStateException.class, () -> game.prepare(context));
        assertTrue(error.getMessage().contains("spawn was rejected"));
        assertTrue(error.getMessage().contains("world"));
        verify(rejected).setDespawnInPeacefulOverride(net.kyori.adventure.util.TriState.FALSE);
        verify(rejected).remove();
        for (var npc : npcs) verify(npc).remove();
    }

    @Test
    void removedNpcCannotSilentlyDisableShots() {
        when(npcs.get(0).isValid()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> game.start(context));
        when(npcs.get(0).isValid()).thenReturn(true);
        game.start(context);
        when(npcs.get(1).isValid()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> game.handleTick(context));
        game.reset(context);
        for (var npc : npcs) verify(npc).remove();
        verify(world, never()).setDifficulty(any());
    }

    private MinigameDefinition definition() {
        return new MinigameDefinition("breeze_tower", "Breeze", true, true, false, 1, 14, 1,
                Optional.of(new CuboidRegion("world", new BlockPosition(117, -33, -151), new BlockPosition(-32, 47, -7))),
                List.of(new LocationSpec(78, 9, -76, 90, 0, true)), Optional.empty(), 60, Map.of(), "test");
    }

    @Test
    void ownedWindChargeBoostsHorizontalForceAndLaunchesEvenAFallingPlayer() {
        game.start(context); tick = 40; game.handleTick(context);
        when(player.getVelocity()).thenReturn(new org.bukkit.util.Vector(0,-0.3,0));
        var hit = new io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent(player,
                io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.EXPLOSION,
                projectiles.getFirst(),new org.bukkit.util.Vector(0.4,0.2,0.1));
        game.onKnockback(context,hit);
        assertEquals(1.04,hit.getKnockback().getX(),0.0001);
        assertEquals(1.45,hit.getKnockback().getY(),0.0001);
        var foreign = new io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent(player,
                io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.EXPLOSION,
                mock(org.bukkit.entity.Entity.class),new org.bukkit.util.Vector(0.4,0.2,0.1));
        game.onKnockback(context,foreign);
        assertEquals(0.2,foreign.getKnockback().getY());
    }

    @Test
    void hiddenPlayersDoNotStopShotsAndFallbackAimsAtSolidPlatform() {
        for (var npc : npcs) when(npc.hasLineOfSight(player)).thenReturn(false);
        var air = mock(org.bukkit.block.Block.class);
        var floor = mock(org.bukkit.block.Block.class);
        when(air.isPassable()).thenReturn(true);
        when(floor.isPassable()).thenReturn(false);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                call.<Integer>getArgument(1) == -3 ? floor : air);
        game.start(context);
        tick = 40;
        game.handleTick(context);
        assertFalse(projectiles.isEmpty());
        verify(projectiles.get(0)).setVelocity(argThat((org.bukkit.util.Vector velocity) -> velocity.getY() < 0));
        verify(world, atLeastOnce()).getBlockAt(anyInt(), eq(-3), anyInt());
    }
}
