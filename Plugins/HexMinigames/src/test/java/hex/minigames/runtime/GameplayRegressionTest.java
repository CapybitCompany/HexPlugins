package hex.minigames.runtime;

import hex.minigames.game.*;
import hex.minigames.game.common.*;
import hex.minigames.game.dalgona.DalgonaMinigame;
import hex.minigames.game.glassbridge.*;
import hex.minigames.game.hothead.*;
import hex.minigames.game.popcorn.*;
import hex.minigames.game.redlight.*;
import hex.minigames.model.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the actual movement -> penalty -> scheduled teleport path used by testareny. */
final class GameplayRegressionTest {
    private final UUID id = UUID.randomUUID();
    private World world;
    private Player player;
    private Plugin plugin;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<RespawnEffects> effects;
    private final Map<Integer, Runnable> tasks = new LinkedHashMap<>();
    private final AtomicReference<Location> position = new AtomicReference<>();
    private int nextTask;
    private MinigamesSessionService service;
    private RoundSession round;
    private RoundContext context;

    @BeforeEach
    void setup() {
        bukkit = mockStatic(Bukkit.class);
        effects = mockStatic(RespawnEffects.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        player = mock(Player.class);
        var server = mock(Server.class);
        when(player.getServer()).thenReturn(server);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Tester");
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getVelocity()).thenReturn(new org.bukkit.util.Vector(0, -0.2, 0));
        when(player.getLocation()).thenAnswer(invocation -> position.get().clone());
        when(player.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenAnswer(invocation -> {
            position.set(((Location) invocation.getArgument(0)).clone());
            return true;
        });
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenAnswer(invocation -> {
            int taskId = ++nextTask;
            tasks.put(taskId, invocation.getArgument(1));
            BukkitTask task = mock(BukkitTask.class);
            when(task.getTaskId()).thenReturn(taskId);
            doAnswer(ignored -> { tasks.remove(taskId); return null; }).when(task).cancel();
            return task;
        });
        doAnswer(invocation -> { tasks.remove(invocation.<Integer>getArgument(0)); return null; })
                .when(scheduler).cancelTask(anyInt());
    }

    @AfterEach
    void cleanup() {
        effects.close();
        bukkit.close();
    }

    @Test
    void redViolationExplodesOnceThenRespawnsAndStartAreaIsSafe() throws Exception {
        var game = new RedLightGreenLightMinigame(plugin);
        attach(game, new LocationSpec(173, -30, -274, 0, 0, true));
        var config = RedLightGreenLightConfig.fromDefinition(round.definition(), new ArrayList<>());
        var runtime = new RedLightGreenLightRuntime(config, new Random(1));
        runtime.forceRed(0);
        set(game, "config", config);
        set(game, "runtime", runtime);
        set(game, "started", true);
        for (int tick = 0; tick < 20; tick++) round.tickElapsed();
        position.set(new Location(world, 173, -30, -260));
        var move = new PlayerMoveEvent(player, position.get(), new Location(world, 173, -30, -259));
        service.handleMove(move);
        assertFalse(move.isCancelled());
        effects.verify(() -> RespawnEffects.explosion(player), times(1));
        verify(player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class));
        verify(player).playSound(any(Location.class), eq("minecraft:entity.villager.no"), eq(1.0f), eq(1.0f));
        for (int i = 0; i < 10; i++) service.handleMove(move);
        effects.verify(() -> RespawnEffects.explosion(player), times(1));
        position.set(move.getTo()); // The movement packet is committed before the scheduled teleport.
        runScheduled();
        assertEquals(-274, position.get().getZ());
        assertEquals(RoundPlayerState.ACTIVE, context.state(id));
        service.handleMove(new PlayerMoveEvent(player, position.get(), new Location(world, 173, -29.5, -272)));
        assertEquals(EventDecision.ALLOW, game.onToggleSneak(context, new PlayerToggleSneakEvent(player, true)));
        effects.verify(() -> RespawnEffects.explosion(player), times(1));
        position.set(new Location(world, 173, -30, -270.2));
        service.handleMove(new PlayerMoveEvent(player, position.get(), new Location(world, 173, -30, -269.8)));
        effects.verify(() -> RespawnEffects.explosion(player), times(2));
    }

    @Test
    void glassFallTeleportsToParticipantSpawnThenDelayPushesBackWithoutFreezingY() throws Exception {
        var game = new GlassBridgeMinigame(plugin);
        var spawn = new LocationSpec(213, 27, 9, 180, 0, true);
        attach(game, spawn);
        var config = GlassBridgeConfig.fromDefinition(round.definition(), new ArrayList<>());
        set(game, "config", config);
        set(game, "runtime", new GlassBridgeRuntime(config, List.of(id), new Random(2)));
        set(game, "started", true);
        position.set(new Location(world, 210, -25, -12));
        var fall = new PlayerMoveEvent(player, position.get(), new Location(world, 210, -27, -12));
        service.handleMove(fall);
        assertFalse(fall.isCancelled());
        assertEquals(RoundPlayerState.RESPAWN_DELAY, context.state(id));
        position.set(fall.getTo());
        runScheduled();
        assertEquals(spawn.toLocation("world"), position.get());
        verify(player).sendTitle(contains("DELAY"), contains("10 s"), eq(0), eq(25), eq(0));
        var boundary = new PlayerMoveEvent(player, new Location(world, 212, 28, 3.2), new Location(world, 212, 27.8, 2.8));
        service.handleMove(boundary);
        assertFalse(boundary.isCancelled());
        assertEquals(27.8, boundary.getTo().getY());
        assertTrue(boundary.getTo().getZ() > 3);
        verify(player).setVelocity(new org.bukkit.util.Vector(0, -0.2, 0.75));
        for (int second = 0; second < 10; second++) runScheduled();
        assertEquals(RoundPlayerState.ACTIVE, context.state(id));
    }

    @Test
    void rejectedTeleportRetriesWithoutClearingRespawnGuard() throws Exception {
        attach(() -> "glass_bridge", new LocationSpec(212, 27, 8, 180, 0, true));
        position.set(new Location(world, 210, -27, -10));
        var arrival = mock(Runnable.class);
        when(player.teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN))).thenReturn(false, true);
        context.respawn(player, new Location(world, 212, 27, 8), arrival);
        runScheduled();
        verify(arrival, never()).run();
        runScheduled();
        verify(arrival).run();
        verify(player, times(2)).teleport(any(Location.class), eq(PlayerTeleportEvent.TeleportCause.PLUGIN));
    }

    @Test
    void allThreeGamesActuallySendTheirCountdownToPlayers() throws Exception {
        for (Minigame game : List.of(new DalgonaMinigame(plugin), new PopcornMinigame(plugin), new HotHeadMinigame(plugin))) {
            attach(game, new LocationSpec(0, 0, 0, 0, 0, true));
            if (game instanceof PopcornMinigame) {
                set(game, "runtime", mock(PopcornRuntime.class));
                set(game, "blocks", mock(BlockChangeTracker.class));
                set(game, "started", true);
                position.set(new Location(world, 0, 0, 0));
            }
            if (game instanceof HotHeadMinigame) {
                set(game, "runtime", mock(HotHeadRuntime.class));
                set(game, "blocks", mock(BlockChangeTracker.class));
                set(game, "config", HotHeadConfig.fromDefinition(round.definition(), new ArrayList<>()));
            }
            clearInvocations(player);
            game.handleTick(context);
            verify(player).sendActionBar(any(Component.class));
        }
        assertEquals("01:30", RoundClock.remaining(90, 0));
        assertEquals("00:01", RoundClock.remaining(90, 1799));
        assertEquals("00:00", RoundClock.remaining(90, 1800));
    }

    private void runScheduled() {
        for (Runnable task : List.copyOf(tasks.values())) task.run();
    }

    @Test
    void lobbyRestoresPlayerAndLeavesTheSessionBeforeTeleportingToWorldSpawn() throws Exception {
        attach(() -> "popcorn", new LocationSpec(0, 0, 0, 0, 0, true));
        position.set(new Location(world, 0, 0, 0));
        var config = mock(hex.minigames.config.LoadedMinigamesConfig.class);
        var global = mock(hex.minigames.config.GlobalConfig.class);
        when(config.global()).thenReturn(global);
        when(config.messages()).thenReturn(new hex.minigames.config.Messages(Map.of()));
        when(global.series()).thenReturn(new hex.minigames.config.SeriesConfig(1));
        set(service, "config", config);
        var repository = mock(hex.minigames.persistence.PlayerSnapshotRepository.class);
        var snapshot = mock(hex.minigames.persistence.StoredPlayerState.class);
        when(repository.find(id)).thenReturn(Optional.of(snapshot));
        set(service, "snapshots", repository);
        var lobby = new Location(world, 100, 70, 100);
        when(world.getSpawnLocation()).thenReturn(lobby);
        when(player.teleport(lobby)).thenAnswer(ignored -> {
            assertFalse(service.activeSessionContains(id));
            position.set(lobby);
            return true;
        });
        try (var restoration = mockStatic(hex.minigames.persistence.PlayerSnapshotRepository.class)) {
            restoration.when(() -> hex.minigames.persistence.PlayerSnapshotRepository.restore(player, snapshot)).thenReturn(true);
            var event = new org.bukkit.event.player.PlayerCommandPreprocessEvent(player, "/lobby");
            service.handleCommand(event);
            assertTrue(event.isCancelled());
            restoration.verify(() -> hex.minigames.persistence.PlayerSnapshotRepository.restore(player, snapshot));
            verify(repository).delete(id);
        }
        assertEquals(lobby, position.get());
        assertFalse(service.activeSessionContains(id));
    }

    @Test
    void dalgonaUsesOpaqueDisplayAndAcceptsTheBlocksRepresentedByItsVisiblePattern() throws Exception {
        var game = new DalgonaMinigame(plugin);
        attach(game, new LocationSpec(435, -31, -229, 180, 0, true));
        position.set(new Location(world, 435, -31, -229));
        var config = hex.minigames.game.dalgona.DalgonaConfig.fromDefinition(round.definition(), new ArrayList<>());
        var runtime = new hex.minigames.game.dalgona.DalgonaRuntime(config, List.of(id), new Random(4));
        var tracker = mock(BlockChangeTracker.class);
        set(game, "config", config);
        set(game, "runtime", runtime);
        set(game, "blocks", tracker);
        set(game, "gameplayStarted", true);
        game.start(context);
        verify(player).setGameMode(GameMode.SURVIVAL);
        var render = DalgonaMinigame.class.getDeclaredMethod("renderStations");
        render.setAccessible(true);
        render.invoke(game);
        var station = runtime.station(id);
        var display = hex.minigames.game.dalgona.DalgonaRuntime.displayRegion(station);
        verify(tracker).fill(argThat(region -> region.contains(display) && display.contains(region)), eq(Material.SMOOTH_SANDSTONE));
        var background = mock(org.bukkit.block.Block.class);
        when(background.getWorld()).thenReturn(world);
        when(background.getType()).thenReturn(Material.SAND);
        when(background.getX()).thenReturn(station.arena().minX());
        when(background.getY()).thenReturn(station.arena().minY());
        when(background.getZ()).thenReturn(station.arena().minZ());
        assertFalse(runtime.requiredBlocks(id).contains(new BlockPosition(background.getX(), background.getY(), background.getZ())));
        service.routeInteract(new org.bukkit.event.player.PlayerInteractEvent(player, org.bukkit.event.block.Action.LEFT_CLICK_BLOCK,
                null, background, org.bukkit.block.BlockFace.UP, org.bukkit.inventory.EquipmentSlot.HAND));
        assertFalse(runtime.eliminated(id), "Clicking must never trigger the explosion before a block is broken");
        for (BlockPosition visible : runtime.boardBlocks(station)) {
            verify(tracker).setType(visible, config.boardMaterial());
            var block = mock(org.bukkit.block.Block.class);
            when(block.getWorld()).thenReturn(world);
            when(block.getType()).thenReturn(Material.SAND);
            when(block.getX()).thenReturn(station.arena().minX() + visible.x() - display.minX());
            when(block.getY()).thenReturn(station.arena().minY());
            when(block.getZ()).thenReturn(station.arena().maxZ() - (visible.y() - display.minY()));
            var event = new org.bukkit.event.player.PlayerInteractEvent(player, org.bukkit.event.block.Action.LEFT_CLICK_BLOCK,
                    null, block, org.bukkit.block.BlockFace.UP, org.bukkit.inventory.EquipmentSlot.HAND);
            int before = runtime.progress(id);
            service.routeInteract(event);
            assertEquals(before, runtime.progress(id), "A click must not cut the sand");
            assertEquals(org.bukkit.event.Event.Result.ALLOW, event.useItemInHand());
            var damage = mock(org.bukkit.event.block.BlockDamageEvent.class);
            when(damage.getPlayer()).thenReturn(player);
            when(damage.getBlock()).thenReturn(block);
            service.routeBlockDamage(damage);
            verify(damage).setInstaBreak(false);
            assertEquals(before, runtime.progress(id));
            service.routeBlockBreak(new org.bukkit.event.block.BlockBreakEvent(block, player));
            assertFalse(runtime.eliminated(id));
        }
        assertEquals(RoundPlayerState.FINISHED, context.state(id));
    }

    @Test
    void memoryPlotPushesBackWithoutCancellingFallingOrTeleporting() throws Exception {
        var game = new hex.minigames.game.supermemory.SuperMemoryMinigame(plugin);
        attach(game, new LocationSpec(5, 1, 5, 0, 0, true));
        var region = new CuboidRegion("world", new BlockPosition(0, 0, 0), new BlockPosition(10, 5, 10));
        var runtime = mock(hex.minigames.game.supermemory.SuperMemoryRuntime.class);
        when(runtime.station(id)).thenReturn(new hex.minigames.game.supermemory.SuperMemoryConfig.StationConfig(
                1, region, new LocationSpec(5, 1, 5, 0, 0, true), List.of()));
        set(game, "runtime", runtime);
        set(game, "config", mock(hex.minigames.game.supermemory.SuperMemoryConfig.class));
        position.set(new Location(world, 10.7, 3, 5));
        var event = new PlayerMoveEvent(player, position.get(), new Location(world, 11.1, 2.8, 5));
        service.handleMove(event);
        assertFalse(event.isCancelled());
        assertEquals(2.8, event.getTo().getY());
        assertEquals(10.7, event.getTo().getX());
        verify(player).setVelocity(new org.bukkit.util.Vector(-0.4, -0.2, 0));
        verify(player, never()).teleport(any(Location.class));
    }

    @Test
    void popcornFallExplodesOnceAndTeleportsSpectatorAbovePlatform() throws Exception {
        var game = new PopcornMinigame(plugin);
        attach(game, new LocationSpec(595, -24, -128, 0, 0, true));
        set(game, "config", PopcornConfig.fromDefinition(round.definition(), new ArrayList<>()));
        var loaded = mock(hex.minigames.config.LoadedMinigamesConfig.class);
        var global = mock(hex.minigames.config.GlobalConfig.class);
        when(loaded.global()).thenReturn(global);
        when(global.ghostAllowFlight()).thenReturn(true);
        set(service, "config", loaded);
        position.set(new Location(world, 595, -29, -128));
        var fall = new PlayerMoveEvent(player, position.get(), new Location(world, 595, -31, -128));
        game.onMove(context, fall);
        game.onMove(context, fall);
        effects.verify(() -> RespawnEffects.explosion(player), times(1));
        assertEquals(RoundPlayerState.GHOST, context.state(id));
        verify(player).setFlying(true);
        verify(player).setGameMode(GameMode.SPECTATOR);
        runScheduled();
        assertEquals(594.5, position.get().getX());
        assertEquals(-20, position.get().getY());
        assertEquals(-131.0, position.get().getZ());
    }

    @Test
    void redLightFinishCannotBeCrossedBackwardsAfterSuccess() throws Exception {
        var game = new RedLightGreenLightMinigame(plugin);
        attach(game, new LocationSpec(173, -30, -274, 0, 0, true));
        var config = RedLightGreenLightConfig.fromDefinition(round.definition(), new ArrayList<>());
        set(game, "config", config);
        set(game, "runtime", new RedLightGreenLightRuntime(config, new Random(1)));
        set(game, "started", true);
        round.playerState(id, RoundPlayerState.FINISHED);
        position.set(new Location(world, 173, -29, -188.5));
        var event = new PlayerMoveEvent(player, position.get(), new Location(world, 173, -29.2, -190));
        service.handleMove(event);
        assertFalse(event.isCancelled());
        assertTrue(event.getTo().getZ() > config.finishRegion().minZ());
        assertEquals(-29.2, event.getTo().getY());
        assertEquals(RoundPlayerState.FINISHED, context.state(id));
        effects.verifyNoInteractions();
    }

    @Test
    void hotHeadPreparationDoesNotConsumePlayingTimeOrRunWaves() throws Exception {
        Minigame game = mock(Minigame.class);
        when(game.id()).thenReturn("hot_head");
        when(game.startDelayTicks()).thenReturn(100);
        attach(game, new LocationSpec(0, 0, 0, 0, 0, true));
        set(round, "startDelayRemaining", 100);
        var sessionField = MinigamesSessionService.class.getDeclaredField("activeSession");
        sessionField.setAccessible(true);
        var session = (MinigamesSession) sessionField.get(service);
        var tick = MinigamesSessionService.class.getDeclaredMethod("tickRunning", MinigamesSession.class);
        tick.setAccessible(true);
        for (int i = 0; i < 100; i++) tick.invoke(service, session);
        verify(game, never()).handleTick(any());
        assertEquals(0, round.elapsedTicks());
        tick.invoke(service, session);
        verify(game).handleTick(any());
        assertEquals(1, round.elapsedTicks());
        assertEquals(100, new HotHeadMinigame(plugin).startDelayTicks());
    }

    @Test
    void successIsAnnouncedOnceWithSubtitleOnlyAndMatchingSound() throws Exception {
        attach(() -> "dalgona", new LocationSpec(0, 0, 0, 0, 0, true));
        position.set(new Location(world, 0, 0, 0));
        context.state(id, RoundPlayerState.FINISHED);
        context.state(id, RoundPlayerState.FINISHED);
        verify(player, times(1)).sendTitle(eq(""), eq(hex.minigames.util.Text.color("&aSUKCES")), eq(0), eq(70), eq(10));
        verify(player, times(1)).sendMessage(hex.minigames.util.Text.color("&fGracz &6Tester &f- &aSukces!"));
        verify(player, times(1)).playSound(any(Location.class), eq("minecraft:entity.player.levelup"), eq(1.0f), eq(1.2f));
        RoundFeedback.show(player, false, true);
        verify(player).sendTitle(eq(""), eq(hex.minigames.util.Text.color("&cPORAŻKA")), eq(0), eq(70), eq(10));
        verify(player).playSound(any(Location.class), eq("minecraft:entity.villager.no"), eq(1.0f), eq(0.8f));
        assertEquals("&fGracz &6Tester &f- &cWyeliminowany!", RoundFeedback.announcement("Tester", false));
    }

    @Test
    void participantsCannotRunCommandsOrNamespacedBypasses() throws Exception {
        attach(() -> "popcorn", new LocationSpec(0, 0, 0, 0, 0, true));
        for (String command : List.of("/op Tester", "/minecraft:tp ~ ~ ~", "/help", "/lobby extra", "/plugin:lobby")) {
            var event = new org.bukkit.event.player.PlayerCommandPreprocessEvent(player, command);
            service.handleCommand(event);
            assertTrue(event.isCancelled(), command);
        }
        assertTrue(MinigamesSessionService.isLobbyCommand("/LOBBY"));
        assertTrue(MinigamesSessionService.isLobbyCommand("/lobby "));
    }

    @Test
    void hotHeadContactBurnsForTwoSecondsWithoutFireTicksRestartingTheTimer() throws Exception {
        var game = new HotHeadMinigame(plugin);
        attach(game, new LocationSpec(-18, -31, 39, 0, 0, true));
        position.set(new Location(world, -18, -31, 39));
        set(game, "config", HotHeadConfig.fromDefinition(round.definition(), new ArrayList<>()));
        set(game, "runtime", mock(HotHeadRuntime.class));
        set(game, "blocks", mock(BlockChangeTracker.class));
        when(player.getHealth()).thenReturn(20.0);
        var damage = mock(org.bukkit.event.entity.EntityDamageEvent.class);
        when(damage.getEntity()).thenReturn(player);
        when(damage.getFinalDamage()).thenReturn(1.0);
        when(damage.getCause()).thenReturn(org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE);
        assertEquals(EventDecision.ALLOW, game.onDamage(context, damage));
        verify(player).setFireTicks(40);
        for (int i = 0; i < 20; i++) round.tickElapsed();
        when(damage.getCause()).thenReturn(org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE_TICK);
        assertEquals(EventDecision.ALLOW, game.onDamage(context, damage));
        clearInvocations(player);
        game.handleTick(context);
        verify(player).setFireTicks(20);
        for (int i = 0; i < 20; i++) round.tickElapsed();
        clearInvocations(player);
        game.handleTick(context);
        verify(player).setFireTicks(0);
        assertEquals(EventDecision.DENY, game.onDamage(context, damage));
    }

    @Test
    void popcornStartsManyBlocksTogetherAndKeepsEachWarningColorReadable() {
        var definition = new MinigameDefinition("popcorn", "Popcorn", true, true, false, 1, 14, 1,
                Optional.of(new CuboidRegion("world", new BlockPosition(0, 0, 0), new BlockPosition(100, 10, 100))),
                List.of(), Optional.empty(), 90, Map.of("hazard", Map.of("stage-duration-ticks", 4,
                "activation-rate-start", 18, "activation-rate-end", 48)), "test");
        var config = PopcornConfig.fromDefinition(definition, new ArrayList<>());
        assertEquals(12, config.stageDurationTicks());
        var runtime = new PopcornRuntime(config, new Random(1));
        assertTrue(runtime.tick(0).isEmpty());
        assertTrue(runtime.tick(10).isEmpty());
        assertTrue(runtime.tick(21).isEmpty());
        var firstGroup = runtime.tick(22);
        assertTrue(firstGroup.size() > 1);
        assertTrue(firstGroup.stream().allMatch(c -> c.material() == Material.YELLOW_CONCRETE));
        assertTrue(runtime.tick(23).isEmpty());
        assertTrue(runtime.tick(34).stream().anyMatch(c -> c.material() == Material.ORANGE_CONCRETE));
    }

    @Test
    void operatorCommandsAreAllowedButGameplayRestrictionsRemain() throws Exception {
        attach(() -> "popcorn", new LocationSpec(0, 0, 0, 0, 0, true));
        when(player.isOp()).thenReturn(true);
        for (String command : List.of("/tp Tester", "/minecraft:give Tester stone", "/hexminigames stop")) {
            var event = new org.bukkit.event.player.PlayerCommandPreprocessEvent(player, command);
            service.handleCommand(event);
            assertFalse(event.isCancelled());
        }
        var block = mock(org.bukkit.event.block.BlockBreakEvent.class);
        when(block.getPlayer()).thenReturn(player);
        service.routeBlockBreak(block);
        verify(block).setCancelled(true);
        assertEquals(RoundPlayerState.ACTIVE, context.state(id));
    }

    @Test
    void dalgonaBoundaryPushesBackWithoutCancellingFalling() throws Exception {
        var game = new DalgonaMinigame(plugin);
        attach(game, new LocationSpec(435, -31, -229, 0, 0, true));
        var config = hex.minigames.game.dalgona.DalgonaConfig.fromDefinition(round.definition(), new ArrayList<>());
        var runtime = new hex.minigames.game.dalgona.DalgonaRuntime(config, List.of(id), new Random(1));
        set(game, "runtime", runtime);
        set(game, "config", config);
        var arena = runtime.station(id).arena();
        position.set(new Location(world, arena.minX() + 0.2, -30, arena.minZ() + 2));
        var movement = new PlayerMoveEvent(player, position.get(),
                new Location(world, arena.minX() - 0.2, -30.2, arena.minZ() + 2));
        service.handleMove(movement);
        assertFalse(movement.isCancelled());
        assertEquals(-30.2, movement.getTo().getY());
        assertTrue(movement.getTo().getX() > arena.minX());
        verify(player).setVelocity(new org.bukkit.util.Vector(0.4, -0.2, 0));
        verify(player, never()).teleport(any(Location.class));
    }

    @Test
    void bridgeEndsOnlyAfterEveryoneFinishesIncludingPlayersWaitingToRespawn() throws Exception {
        UUID second = UUID.randomUUID();
        var game = new GlassBridgeMinigame(plugin);
        attach(game, new LocationSpec(212, 27, 8, 0, 0, true), Set.of(id, second));
        var config = GlassBridgeConfig.fromDefinition(round.definition(), new ArrayList<>());
        var runtime = new GlassBridgeRuntime(config, context.participants(), new Random(1));
        set(game, "config", config);
        set(game, "runtime", runtime);
        set(game, "started", true);
        round.playerState(second, RoundPlayerState.RESPAWN_DELAY);
        position.set(new Location(world, 212, 27, -66.5));
        service.handleMove(new PlayerMoveEvent(player, position.get(), new Location(world, 212, 27, -67)));
        assertEquals(RoundPlayerState.FINISHED, context.state(id));
        assertFalse(round.finishRequested());
        game.handleTick(context);
        assertFalse(round.finishRequested());
        runtime.finish(second);
        round.playerState(second, RoundPlayerState.FINISHED);
        game.handleTick(context);
        assertTrue(round.finishRequested());
        assertEquals(4, runtime.score(id));
    }

    @Test
    void bridgePvpRoutesOnlyActiveParticipantsAndAnnouncesEachTransitionOnce() throws Exception {
        UUID attackerId = UUID.randomUUID();
        Player attacker = mock(Player.class);
        when(attacker.getUniqueId()).thenReturn(attackerId);
        when(player.getHealth()).thenReturn(20.0);
        var game = new GlassBridgeMinigame(plugin);
        attach(game, new LocationSpec(212, 27, 8, 0, 0, true), Set.of(id, attackerId));
        var config = GlassBridgeConfig.fromDefinition(round.definition(), new ArrayList<>());
        var schedule = new GlassBridgePvpSchedule(1800, new Random(1));
        set(game, "config", config);
        set(game, "runtime", new GlassBridgeRuntime(config, context.participants(), new Random(1)));
        set(game, "pvpSchedule", schedule);
        set(game, "started", true);
        position.set(new Location(world, 212, 27, 8));
        var attack = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
        when(attack.getDamager()).thenReturn(attacker);
        when(attack.getEntity()).thenReturn(player);
        when(attack.getFinalDamage()).thenReturn(1.0);
        var router = new hex.minigames.listener.MinigamesEventRouter(service);
        router.onDamageByEntity(attack);
        verify(attack).setCancelled(true);
        clearInvocations(attack);
        while (context.elapsedTicks() < schedule.starts().get(0)) round.tickElapsed();
        game.handleTick(context);
        game.handleTick(context);
        verify(player, atLeastOnce()).sendActionBar(argThat((Component message) ->
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message).contains("| PVP ON")));
        verify(player, never()).sendTitle(eq(""), contains("ON"), anyInt(), anyInt(), anyInt());
        router.onDamage(attack);
        router.onDamageByEntity(attack);
        verify(attack, never()).setCancelled(true);
        round.playerState(attackerId, RoundPlayerState.FINISHED);
        router.onDamageByEntity(attack);
        verify(attack).setCancelled(true);
        round.playerState(attackerId, RoundPlayerState.ACTIVE);
        clearInvocations(attack);
        for (int tick = 0; tick < 60; tick++) round.tickElapsed();
        game.handleTick(context);
        verify(player, atLeastOnce()).sendActionBar(argThat((Component message) ->
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message).contains("| PVP OFF")));
        verify(player, times(1)).playSound(any(Location.class), eq("minecraft:block.note_block.bell"), eq(0.8f), eq(1.2f));
        verify(player, times(1)).playSound(any(Location.class), eq("minecraft:block.note_block.chime"), eq(0.8f), eq(0.7f));
        router.onDamageByEntity(attack);
        verify(attack).setCancelled(true);
    }

    private void attach(Minigame game, LocationSpec spawn) throws Exception {
        attach(game, spawn, Set.of(id));
    }

    @Test
    void popcornIdleWarningAfterOneAndHalfSecondsThenEliminatesAtThreeAndHalfSeconds() throws Exception {
        var game = new PopcornMinigame(plugin);
        attach(game, new LocationSpec(0, 0, 0, 0, 0, true));
        var loaded = mock(hex.minigames.config.LoadedMinigamesConfig.class);
        var global = mock(hex.minigames.config.GlobalConfig.class);
        when(loaded.global()).thenReturn(global);
        when(global.ghostAllowFlight()).thenReturn(true);
        set(service, "config", loaded);
        position.set(new Location(world, 0, 0, 0));
        set(game, "config", PopcornConfig.fromDefinition(round.definition(), new ArrayList<>()));
        set(game, "runtime", mock(PopcornRuntime.class));
        set(game, "blocks", mock(BlockChangeTracker.class));
        game.start(context);
        for (int i = 0; i < 29; i++) round.tickElapsed();
        game.handleTick(context);
        verify(player, never()).sendActionBar(argThat((Component message) ->
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message).contains("Eliminacja za")));
        round.tickElapsed();
        game.handleTick(context);
        verify(player).sendActionBar(argThat((Component message) ->
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message).contains("Eliminacja za 2 s")));
        effects.verifyNoInteractions();
        position.set(new Location(world, 0.5, 0, 0));
        game.handleTick(context);
        for (int i = 0; i < 69; i++) round.tickElapsed();
        game.handleTick(context);
        assertEquals(RoundPlayerState.ACTIVE, context.state(id));
        round.tickElapsed();
        game.handleTick(context);
        game.handleTick(context);
        assertEquals(RoundPlayerState.GHOST, context.state(id));
        verify(player).setGameMode(GameMode.SPECTATOR);
        effects.verify(() -> RespawnEffects.explosion(player), times(1));
        runScheduled();
        assertEquals(PopcornConfig.fromDefinition(round.definition(), new ArrayList<>()).platform().maxY() + 6.0, position.get().getY());
    }

    @Test
    void redLightTimeoutExplodesOnlyUnfinishedPlayersAndOnlyOnce() throws Exception {
        var game = new RedLightGreenLightMinigame(plugin);
        attach(game, new LocationSpec(173, -30, -274, 0, 0, true));
        var config = RedLightGreenLightConfig.fromDefinition(round.definition(), new ArrayList<>());
        set(game, "config", config);
        set(game, "runtime", new RedLightGreenLightRuntime(config, new Random(1)));
        game.finish(context, RoundEndReason.TIME_LIMIT);
        game.finish(context, RoundEndReason.TIME_LIMIT);
        effects.verify(() -> RespawnEffects.explosion(player), times(1));
    }

    private void attach(Minigame game, LocationSpec spawn, Set<UUID> participants) throws Exception {
        var definition = new MinigameDefinition(game.id(), game.id(), true, true, false, 1, 14, 1,
                Optional.of(new CuboidRegion("world", new BlockPosition(-1000, -100, -1000), new BlockPosition(1000, 100, 1000))),
                List.of(spawn), Optional.of(spawn), 90, Map.of(), "test");
        var session = new MinigamesSession(UUID.randomUUID(), SessionMode.ADMIN_TEST, null, participants, List.of(definition));
        session.state(SeriesState.ROUND_RUNNING);
        round = new RoundSession(1, definition, game, participants, 1800);
        while (round.startDelayRemaining() > 0) round.tickStartDelay();
        session.currentRound(round);
        service = new MinigamesSessionService(plugin, null, null, new MinigameRegistry());
        set(service, "activeSession", session);
        context = new RoundContext(service, round);
    }

    @Test
    void wrongGlassCannotBeUsedForImmediateBunnyHopEvenFromAnEdge() throws Exception {
        var game = new GlassBridgeMinigame(plugin);
        attach(game, new LocationSpec(212, 27, 8, 0, 0, true));
        var config = GlassBridgeConfig.fromDefinition(round.definition(), new ArrayList<>());
        var runtime = new GlassBridgeRuntime(config, Set.of(id), new Random(1));
        set(game, "config", config);
        set(game, "runtime", runtime);
        set(game, "blocks", mock(BlockChangeTracker.class));
        set(game, "started", true);
        var pair = config.pairs().getFirst();
        var bad = runtime.safeSide(pair.index()) == GlassBridgeRuntime.Side.LEFT ? pair.right() : pair.left();
        double edgeX = bad.region().minX() - 0.2;
        position.set(new Location(world, edgeX, 27, -1.5));
        var jump = new PlayerMoveEvent(player, position.get(), new Location(world, edgeX, 27.42, -1.8));
        game.onMove(context, jump);
        game.onMove(context, jump);
        assertEquals(RoundPlayerState.ACTIVE, context.state(id));
        assertFalse(runtime.fallTriggered(id));
        effects.verifyNoInteractions();
        var takeoff = new com.destroystokyo.paper.event.player.PlayerJumpEvent(player,position.get(),position.get().clone().add(0,0.42,0));
        service.routeJump(takeoff);
        assertTrue(takeoff.isCancelled());
        verify(player).setVelocity(new org.bukkit.util.Vector(0, -0.08, 0));
        game.onMove(context,new PlayerMoveEvent(player,position.get(),new Location(world,edgeX,config.fallY(),-1.5)));
        assertEquals(RoundPlayerState.RESPAWN_DELAY,context.state(id));
        effects.verify(() -> RespawnEffects.explosion(player), times(1));
        runScheduled();
        assertEquals(config.respawnSpawn().toLocation("world"), position.get());
    }

    @Test
    void safeGlassAllowsJumpingAndFlyingAboveBadGlassIsNotALanding() throws Exception {
        var game = new GlassBridgeMinigame(plugin);
        attach(game, new LocationSpec(212, 27, 8, 0, 0, true));
        var config = GlassBridgeConfig.fromDefinition(round.definition(), new ArrayList<>());
        var runtime = new GlassBridgeRuntime(config, Set.of(id), new Random(1));
        set(game, "config", config);
        set(game, "runtime", runtime);
        set(game, "started", true);
        var pair = config.pairs().getFirst();
        var safe = runtime.safeSide(pair.index()) == GlassBridgeRuntime.Side.LEFT ? pair.left() : pair.right();
        var bad = safe == pair.left() ? pair.right() : pair.left();
        position.set(new Location(world, safe.region().minX() + 1, 27, -1.5));
        game.onMove(context, new PlayerMoveEvent(player, position.get(),
                new Location(world, position.get().getX(), 27.42, -1.8)));
        assertEquals(RoundPlayerState.ACTIVE, context.state(id));
        var aboveBad = new Location(world, bad.region().minX() + 1, 28.1, -1.5);
        game.onMove(context, new PlayerMoveEvent(player, aboveBad, aboveBad.clone().add(0, -0.1, -0.2)));
        assertEquals(RoundPlayerState.ACTIVE, context.state(id));
        effects.verifyNoInteractions();
        // A downward packet crossing the deck must count even if neither endpoint is at Y=27.
        set(game, "blocks", mock(BlockChangeTracker.class));
        game.onMove(context, new PlayerMoveEvent(player, aboveBad, aboveBad.clone().add(0, -1.5, 0)));
        assertEquals(RoundPlayerState.ACTIVE, context.state(id));
        effects.verifyNoInteractions();
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void resultScreenNeverRoutesMovementBackToResetStartBarrier() throws Exception {
        for (String id : List.of("glass_bridge", "jump_rope")) {
            Minigame game = mock(Minigame.class);
            when(game.id()).thenReturn(id);
            attach(game, new LocationSpec(0, 0, 0, 0, 0, true));
            var field = MinigamesSessionService.class.getDeclaredField("activeSession");
            field.setAccessible(true);
            ((MinigamesSession) field.get(service)).state(SeriesState.ROUND_RESULTS);
            Location end = new Location(world, 450, 12, -83);
            var move = new PlayerMoveEvent(player, end, end.clone().add(0, 0, -0.2));
            service.handleMove(move);
            assertEquals(end, move.getTo());
            verify(game, never()).onMove(any(), any());
            verify(player, never()).teleport(any(Location.class));
        }
    }

    @Test
    void hotHeadGhostIsConfinedToRequestedPlatformVolume() throws Exception {
        var game = new HotHeadMinigame(plugin);
        attach(game, new LocationSpec(-18, -31, 39, 0, 0, true));
        round.playerState(id, RoundPlayerState.GHOST);
        var move = new PlayerMoveEvent(player, new Location(world, -18, -31, 39), new Location(world, 100, 20, -100));
        service.handleMove(move);
        assertTrue(game.ghostRegion(round.definition()).orElseThrow().contains(move.getTo()));
        verify(player, never()).teleport(any(Location.class));
    }
}
