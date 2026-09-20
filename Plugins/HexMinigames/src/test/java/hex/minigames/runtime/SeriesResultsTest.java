package hex.minigames.runtime;
import hex.minigames.config.*;
import hex.minigames.game.*;
import hex.minigames.model.*;
import hex.minigames.persistence.PlayerSnapshotRepository;
import hex.minigames.score.ScoreService;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

final class SeriesResultsTest {
    @Test void adminSeriesDrawsFiveDistinctGamesForSelectedPlayerCount() throws Exception {
        var registry = mock(MinigameRegistry.class);
        List<MinigameDefinition> pool = new ArrayList<>();
        for (int i = 0; i < 7; i++) pool.add(new MinigameDefinition("game" + i, "Game", true, true, false,
                1,14,1,Optional.empty(),List.of(),Optional.empty(),90,Map.of(),"test"));
        when(registry.eligible(2, false, false)).thenReturn(pool);
        var service = new MinigamesSessionService(mock(Plugin.class), null, mock(ScoreService.class), registry);
        var config = mock(LoadedMinigamesConfig.class, RETURNS_DEEP_STUBS);
        when(config.global().gamesPerSeries()).thenReturn(2);
        set(service,"config",config);
        var session = new MinigamesSession(UUID.randomUUID(),SessionMode.ADMIN_TEST,null,
                Set.of(UUID.randomUUID(),UUID.randomUUID()),List.of());
        set(service,"activeSession",session);
        try (var bukkit = mockStatic(Bukkit.class)) {
            invoke(service,"freezeSelectedGamesAfterDraw",session);
        }
        assertEquals(5, session.selectedGames().size());
        assertEquals(5, new HashSet<>(session.selectedGames()).size());
        assertTrue(pool.containsAll(session.selectedGames()));
        assertEquals(SeriesState.PRE_GAME_COUNTDOWN,session.state());
        verify(registry).eligible(2,false,false);
    }
    @Test void lobbyAndSummaryAllowJumping() throws Exception {
        var service = new MinigamesSessionService(mock(Plugin.class), null, mock(ScoreService.class), new MinigameRegistry());
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID(); when(player.getUniqueId()).thenReturn(id);
        var session = new MinigamesSession(UUID.randomUUID(), SessionMode.ADMIN_TEST, null, Set.of(id), List.of());
        set(service, "activeSession", session);
        for (SeriesState state : List.of(SeriesState.PRE_GAME_WAITING, SeriesState.PRE_GAME_DRAW,
                SeriesState.PRE_GAME_COUNTDOWN, SeriesState.SERIES_RESULTS, SeriesState.INTERMISSION)) {
            session.state(state);
            var event = mock(com.destroystokyo.paper.event.player.PlayerJumpEvent.class);
            when(event.getPlayer()).thenReturn(player);
            service.routeJump(event);
            verify(event, never()).setCancelled(true);
        }
    }

    @Test void completionRestoresSnapshotWithoutTeleportingBackToMinigames() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var restore = mockStatic(PlayerSnapshotRepository.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(mock(org.bukkit.scheduler.BukkitScheduler.class));
            var snapshots = mock(PlayerSnapshotRepository.class);
            var service = new MinigamesSessionService(mock(Plugin.class), snapshots, mock(ScoreService.class), new MinigameRegistry());
            var config = mock(LoadedMinigamesConfig.class, RETURNS_DEEP_STUBS);
            when(config.messages()).thenReturn(new Messages(Map.of())); set(service, "config", config);
            UUID id = UUID.randomUUID();
            Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            var state = mock(hex.minigames.persistence.StoredPlayerState.class);
            when(snapshots.find(id)).thenReturn(Optional.of(state));
            restore.when(() -> PlayerSnapshotRepository.restore(player, state)).thenReturn(true);
            var session = new MinigamesSession(UUID.randomUUID(), SessionMode.ADMIN_TEST, null, Set.of(id), List.of());
            session.state(SeriesState.SERIES_RESULTS); set(service, "activeSession", session);
            invoke(service, "completeSession", session);
            restore.verify(() -> PlayerSnapshotRepository.restore(player, state));
            verify(snapshots).delete(id);
            verify(player, never()).teleport(any(Location.class));
            assertFalse(service.activeSessionContains(id));
        }
    }
    @Test void summaryReturnsPlayersToLobbyAndShowsEveryPlaceInOrder() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class); bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            Plugin plugin = mock(Plugin.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(mock(org.bukkit.scheduler.BukkitScheduler.class));
            ScoreService scores = mock(ScoreService.class);
            var service = new MinigamesSessionService(plugin, null, scores, new MinigameRegistry());
            var config = mock(LoadedMinigamesConfig.class, RETURNS_DEEP_STUBS);
            var messages = new Messages(Map.of("series-ranking-header", "HEADER", "series-ranking-row", "{place}:{player}:{points}"));
            when(config.messages()).thenReturn(messages);
            when(config.global().worldName()).thenReturn("world");
            when(config.global().pregame().spawn()).thenReturn(new LocationSpec(569,-29,86,-90,0,true));
            when(config.global().seriesResultsSeconds()).thenReturn(12);
            set(service, "config", config);
            Set<UUID> ids = new LinkedHashSet<>(); List<Player> players = new ArrayList<>();
            for (int i = 0; i < 14; i++) {
                UUID id = UUID.randomUUID(); ids.add(id);
                Player player = mock(Player.class); players.add(player);
                when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true);
                bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            }
            var session = new MinigamesSession(UUID.randomUUID(), SessionMode.EVENT, null, ids, List.of());
            int points = 0; for (UUID id : ids) { session.rememberName(id, "P" + points); session.seriesScore().add(id, points++); }
            set(service, "activeSession", session);
            var celebration = mock(SeriesCelebration.class); service.celebration(celebration);
            invoke(service, "showSeriesResults", session);
            assertEquals(SeriesState.SERIES_RESULTS, session.state());
            assertNull(session.currentRound());
            for (Player player : players) {
                verify(player).teleport(new Location(world,569,-29,86,-90,0));
                var order = inOrder(player);
                order.verify(player).sendMessage("HEADER");
                for (int place = 1; place <= 14; place++) order.verify(player).sendMessage(place + ":P" + (14-place) + ":" + (14-place));
            }
            verify(celebration).show(anyCollection(), any(Location.class), eq("P13"), eq(messages));
            verify(scores).commitEligibleSeries(session);
        }
    }
    @Test void failedPreparationReturnsActualCauseInsteadOfSuccess() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            World world = mock(World.class); bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            Plugin plugin = mock(Plugin.class); when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            var snapshots = mock(PlayerSnapshotRepository.class); when(snapshots.available()).thenReturn(true);
            var scores = mock(ScoreService.class); when(scores.available()).thenReturn(true);
            var registry = new MinigameRegistry();
            var game = mock(Minigame.class); doThrow(new IllegalArgumentException("Missing marker 252,57,76")).when(game).prepare(any());
            registry.register(new MinigameFactory() {
                public String id() { return "elytra"; } public boolean internal() { return false; } public Minigame create() { return game; }
            });
            var definition = new MinigameDefinition("elytra","Elytra",true,true,false,0,14,1,Optional.empty(),List.of(),Optional.empty(),90,Map.of(),"test");
            var service = new MinigamesSessionService(plugin,snapshots,scores,registry);
            var config = mock(LoadedMinigamesConfig.class, RETURNS_DEEP_STUBS);
            when(config.valid()).thenReturn(true); when(config.global().worldName()).thenReturn("world");
            when(config.global().maxPlayers()).thenReturn(14); when(config.messages()).thenReturn(new Messages(Map.of()));
            set(service,"config",config);
            Method start = MinigamesSessionService.class.getDeclaredMethod("startSession", UUID.class, SessionMode.class,
                    hex.events.api.EventExecutionContext.class, Set.class, List.class, boolean.class);
            start.setAccessible(true);
            String failure = (String) start.invoke(service,UUID.randomUUID(),SessionMode.ADMIN_TEST,null,Set.of(),List.of(definition),false);
            assertTrue(failure.contains("Missing marker 252,57,76"));
            verify(game).reset(any());
        }
    }
    @Test void threeEventRegistrationsCannotTeleportAnyoneIntoWaitingRoom() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            var world = mock(World.class); bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            var snapshots = mock(PlayerSnapshotRepository.class); when(snapshots.available()).thenReturn(true);
            var scores = mock(ScoreService.class); when(scores.available()).thenReturn(true);
            var service = new MinigamesSessionService(mock(Plugin.class), snapshots, scores, new MinigameRegistry());
            var config = mock(LoadedMinigamesConfig.class, RETURNS_DEEP_STUBS);
            when(config.valid()).thenReturn(true); when(config.global().worldName()).thenReturn("world");
            when(config.global().pregame().minimumPlayers()).thenReturn(4);
            when(config.messages()).thenReturn(new Messages(Map.of("queue-too-few", "MINIMUM {required}")));
            set(service,"config",config);
            Set<UUID> ids = new HashSet<>(); List<Player> players = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                UUID id = UUID.randomUUID(); ids.add(id); var player = mock(Player.class); players.add(player);
                when(player.isOnline()).thenReturn(true); bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            }
            var context = new hex.events.api.EventExecutionContext(UUID.randomUUID(),"test","test",null,null,null,null,ids);
            var result = service.startEvent(context);
            assertFalse(result.success()); assertEquals("MINIMUM 4", result.message());
            verify(snapshots, never()).saveIfAbsent(any(Player.class), any(UUID.class));
            for (Player player : players) { verify(player, never()).teleport(any(Location.class)); verify(player, never()).teleport(any(Location.class), any(PlayerTeleportEvent.TeleportCause.class)); }
            var session = new MinigamesSession(UUID.randomUUID(),SessionMode.EVENT,null,ids,List.of());
            assertFalse(session.canBeginPregame(4));
            session.addParticipant(UUID.randomUUID(),0); assertTrue(session.canBeginPregame(4));
            session.markSeriesStarted();
            while (session.activeParticipantCount() > 2) session.removeParticipant(session.participants().iterator().next());
            assertFalse(session.shouldFinishForMinimumContinuation(2));
            session.removeParticipant(session.participants().iterator().next());
            assertTrue(session.shouldFinishForMinimumContinuation(2));
        }
    }

    private static void invoke(Object target, String name, MinigamesSession session) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, MinigamesSession.class); method.setAccessible(true); method.invoke(target, session);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
