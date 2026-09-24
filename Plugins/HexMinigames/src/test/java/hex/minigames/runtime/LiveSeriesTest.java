package hex.minigames.runtime;

import hex.minigames.config.*;
import hex.minigames.game.*;
import hex.minigames.model.*;
import hex.minigames.persistence.*;
import hex.minigames.score.ScoreService;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Regression coverage for production admission, terminal rewards and durable returns. */
final class LiveSeriesTest {
    @Test void sumoPreAttackWorksDuringVanillaImmunityAndCannotDoubleApplyThroughDamage() throws Exception {
        Fixture f = new Fixture();
        var game = new hex.minigames.game.sumo.SumoMinigame(f.plugin);
        set(game,"started",true);
        UUID attackerId=UUID.randomUUID(), victimId=UUID.randomUUID();
        Player attacker=mock(Player.class), victim=mock(Player.class); World world=mock(World.class);
        when(attacker.getUniqueId()).thenReturn(attackerId); when(victim.getUniqueId()).thenReturn(victimId);
        when(attacker.getLocation()).thenReturn(new Location(world,448,52,327));
        when(victim.getLocation()).thenReturn(new Location(world,448,53,329));
        when(victim.isInvulnerable()).thenReturn(true); when(victim.getNoDamageTicks()).thenReturn(60);
        var definition=new MinigameDefinition("monkey_run","Sumo",true,true,false,1,14,1,Optional.empty(),List.of(),Optional.empty(),90,Map.of(),"test");
        var session=new MinigamesSession(UUID.randomUUID(),SessionMode.ADMIN_TEST,null,Set.of(attackerId,victimId),List.of(definition));
        var round=new RoundSession(1,definition,game,session.participants(),1800);
        session.currentRound(round); session.state(SeriesState.ROUND_RUNNING); set(f.service,"activeSession",session);
        var hit=new io.papermc.paper.event.player.PrePlayerAttackEntityEvent(attacker,victim,true);
        f.service.routePreAttack(hit); f.service.routePreAttack(hit);
        assertTrue(hit.isCancelled());
        verify(victim,times(2)).setVelocity(new org.bukkit.util.Vector(0,0.52,1.65));
        var damage=mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
        when(damage.getEntity()).thenReturn(victim); when(damage.getDamager()).thenReturn(attacker);
        when(damage.getCause()).thenReturn(org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        f.service.routeDamage(victim,damage);
        verify(victim,times(2)).setVelocity(any());
        round.playerState(victimId,RoundPlayerState.RESPAWN_DELAY); f.service.routePreAttack(hit);
        verify(victim,times(2)).setVelocity(any());
        round.playerState(victimId,RoundPlayerState.ACTIVE); f.service.routePreAttack(hit);
        verify(victim,times(3)).setVelocity(any());
        session.state(SeriesState.ROUND_COUNTDOWN); f.service.routePreAttack(hit);
        verify(victim,times(3)).setVelocity(any());
    }

    @Test void tagSingleArenaAcceptsTwoPlayersWithoutChangingProductionMinimum() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(org.bukkit.inventory.ItemStack.class)) {
            Fixture f = new Fixture();
            World lobby = mock(World.class), original = mock(World.class);
            when(original.getName()).thenReturn("world");
            bukkit.when(() -> Bukkit.getWorld("Hex_Minigames")).thenReturn(lobby);
            when(f.config.valid()).thenReturn(true); when(f.snapshots.available()).thenReturn(true); when(f.scores.available()).thenReturn(true);
            when(f.snapshots.saveIfAbsent(any(),any())).thenReturn(true);
            var definition = new MinigameDefinition("tag","Berek",true,true,false,4,14,1,
                    Optional.empty(),List.of(),Optional.empty(),150,Map.of(),"test");
            when(f.registry.definition("tag")).thenReturn(Optional.of(definition));
            Minigame game = mock(Minigame.class);
            when(game.availability(any(),eq(2))).thenReturn(MinigameAvailability.ok());
            when(f.registry.create("tag")).thenReturn(Optional.of(game));
            List<Player> players = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Player player = mock(Player.class, RETURNS_DEEP_STUBS); UUID id = UUID.randomUUID();
                when(player.getUniqueId()).thenReturn(id); when(player.getName()).thenReturn("P" + i);
                when(player.isOnline()).thenReturn(true); when(player.getWorld()).thenReturn(original);
                when(player.getMaxHealth()).thenReturn(20.0); when(player.getActivePotionEffects()).thenReturn(List.of());
                bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player); players.add(player);
            }
            assertTrue(f.service.startAdminSingle("tag",List.of(players.getFirst())).contains("Nieprawidlowa liczba graczy"));
            f.service.startAdminSingle("tag",players);
            for (Player player : players) assertTrue(f.service.activeSessionContains(player.getUniqueId()));
            verify(game).prepare(any());
            assertEquals(4,definition.minPlayers());
            assertFalse(definition.playerCountAllowed(2));
        }
    }

    @Test void tiedLeadersEachReceiveOneCommandAcrossRepeatedResultCallbacks() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Fixture f = new Fixture();
            UUID a = UUID.randomUUID(), b = UUID.randomUUID(), loser = UUID.randomUUID(), quitter = UUID.randomUUID();
            var session = new MinigamesSession(UUID.randomUUID(), SessionMode.ADMIN_TEST, null,
                    Set.of(a, b, loser, quitter), f.games());
            session.rememberName(a, "Alice"); session.rememberName(b, "Bob");
            session.rememberName(loser, "Charlie");
            session.seriesScore().add(a, 9); session.seriesScore().add(b, 9);
            session.seriesScore().add(loser, 8); session.seriesScore().add(quitter, 20);
            session.forfeitParticipant(quitter);
            while (session.hasNextRound()) session.consumeNextGame();
            assertEquals(Set.of(a, b), new HashSet<>(session.winners()));
            set(f.service, "activeSession", session);
            var celebration = mock(SeriesCelebration.class); f.service.celebration(celebration);
            var console = mock(org.bukkit.command.ConsoleCommandSender.class);
            bukkit.when(Bukkit::getConsoleSender).thenReturn(console);
            call(f.service, "showSeriesResults", session);
            call(f.service, "showSeriesResults", session);
            bukkit.verify(() -> Bukkit.dispatchCommand(console, "dajpunkt global Alice 1"), times(1));
            bukkit.verify(() -> Bukkit.dispatchCommand(console, "dajpunkt global Bob 1"), times(1));
            bukkit.verify(() -> Bukkit.dispatchCommand(console, "dajpunkt global Charlie 1"), never());
            verify(celebration).show(anyCollection(), any(), eq(List.of("Alice", "Bob")), any());
            assertEquals(240, session.stateTicksRemaining());
        }
    }

    @Test void singleArenaDoesNotPaySeriesReward() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Fixture f = new Fixture(); UUID id = UUID.randomUUID();
            var session = new MinigamesSession(UUID.randomUUID(), SessionMode.ADMIN_TEST, null, Set.of(id), List.of(f.games().getFirst()));
            session.consumeNextGame(); session.rememberName(id, "Alice");
            set(f.service, "activeSession", session);
            call(f.service, "showSeriesResults", session);
            bukkit.verify(() -> Bukkit.dispatchCommand(any(), anyString()), never());
        }
    }

    @Test void failedReturnIsRetriedWhileSessionIsAlreadyFinished() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var restoration = mockStatic(PlayerSnapshotRepository.class)) {
            Fixture f = new Fixture(); UUID id = UUID.randomUUID();
            Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(id); when(player.isOnline()).thenReturn(true);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));
            var stored = mock(StoredPlayerState.class);
            when(f.snapshots.find(id)).thenReturn(Optional.of(stored));
            restoration.when(() -> PlayerSnapshotRepository.restore(player, stored)).thenReturn(false, true);
            var session = new MinigamesSession(UUID.randomUUID(), SessionMode.ADMIN_TEST, null, Set.of(id), List.of());
            set(f.service, "activeSession", session);
            call(f.service, "completeSession", session);
            verify(f.snapshots, never()).delete(id);
            for (int tick = 0; tick < 20; tick++) call(f.service, "tick");
            restoration.verify(() -> PlayerSnapshotRepository.restore(player, stored), times(2));
            verify(f.snapshots).delete(id);
            assertFalse(f.service.activeSessionContains(id));
        }
    }

    @Test void entryCapturesTheSourceLocationBeforeTeleport() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Fixture f = new Fixture(); when(f.snapshots.available()).thenReturn(true);
            World original = mock(World.class), lobby = mock(World.class);
            when(original.getName()).thenReturn("world"); when(lobby.getName()).thenReturn("Hex_Minigames");
            Player player = mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            Location from = new Location(original, 123, 70, -456, 33, 5);
            var event = new PlayerTeleportEvent(player, from, new Location(lobby, 570, -29, 86));
            f.service.captureLobbyEntry(event);
            verify(f.snapshots).saveIfAbsent(player, null, from);
            assertFalse(event.isCancelled());
        }
    }

    @Test void waitingPlayerCannotDropSavedItemsAndIsRestoredWhenLeavingBeforeStart() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var restoration = mockStatic(PlayerSnapshotRepository.class);
             var items = mockConstruction(org.bukkit.inventory.ItemStack.class)) {
            Fixture f = new Fixture(); World lobby = mock(World.class), original = mock(World.class);
            when(lobby.getName()).thenReturn("Hex_Minigames"); when(original.getName()).thenReturn("world");
            Player player = mock(Player.class, RETURNS_DEEP_STUBS); UUID id = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(id); when(player.getWorld()).thenReturn(lobby);
            when(player.getMaxHealth()).thenReturn(20.0); when(player.getActivePotionEffects()).thenReturn(List.of());
            var stored = mock(StoredPlayerState.class); when(f.snapshots.find(id)).thenReturn(Optional.of(stored));
            f.service.handleChangedWorld(player);
            verify(player.getInventory()).clear();
            assertFalse(f.service.activeSessionContains(id));
            when(player.getWorld()).thenReturn(original);
            restoration.when(() -> PlayerSnapshotRepository.restore(player, stored)).thenReturn(true);
            f.service.handleChangedWorld(player);
            restoration.verify(() -> PlayerSnapshotRepository.restore(player, stored));
            verify(f.snapshots).delete(id);
        }
    }

    @Test void autostartCountsOnlyActualLobbyOccupantsAndClaimsOriginalSnapshots() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class); var items = mockConstruction(org.bukkit.inventory.ItemStack.class)) {
            Fixture f = new Fixture(); World world = mock(World.class); when(world.getName()).thenReturn("Hex_Minigames");
            bukkit.when(() -> Bukkit.getWorld("Hex_Minigames")).thenReturn(world);
            bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));
            bukkit.when(() -> Bukkit.createBossBar(anyString(), any(org.bukkit.boss.BarColor.class), any(org.bukkit.boss.BarStyle.class)))
                    .thenReturn(mock(org.bukkit.boss.BossBar.class));
            when(f.config.valid()).thenReturn(true); when(f.snapshots.available()).thenReturn(true); when(f.scores.available()).thenReturn(true);
            when(f.snapshots.claimLobbySnapshot(any(), any())).thenReturn(true);
            when(f.config.global().pregame().enabled()).thenReturn(true);
            when(f.config.global().pregame().bossBarTitle()).thenReturn("Lobby");
            when(f.config.global().pregame().bossBarColor()).thenReturn(org.bukkit.boss.BarColor.WHITE);
            when(f.config.global().pregame().bossBarStyle()).thenReturn(org.bukkit.boss.BarStyle.SOLID);
            when(f.config.global().pregame().minimumPlayers()).thenReturn(4);
            when(f.config.global().pregame().region()).thenReturn(new CuboidRegion("Hex_Minigames", new BlockPosition(543,-33,56), new BlockPosition(619,-3,116)));
            when(f.registry.eligible(4, false, false)).thenReturn(f.games());
            List<Player> players = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Player p = mock(Player.class, RETURNS_DEEP_STUBS); UUID id = UUID.randomUUID();
                when(p.getUniqueId()).thenReturn(id); when(p.isOnline()).thenReturn(true); when(p.getName()).thenReturn("P" + i);
                when(p.getWorld()).thenReturn(world); when(p.getMaxHealth()).thenReturn(20.0);
                when(p.getActivePotionEffects()).thenReturn(List.of());
                when(p.getLocation()).thenReturn(new Location(world, i == 3 ? 0 : 570, -29, 86));
                bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(p); players.add(p);
            }
            when(world.getPlayers()).thenReturn(players);
            call(f.service, "checkLobbyStart");
            verify(f.snapshots, never()).claimLobbySnapshot(any(), any());
            when(players.get(3).getLocation()).thenReturn(new Location(world,570,-29,86));
            call(f.service, "checkLobbyStart");
            verify(f.snapshots, times(4)).claimLobbySnapshot(any(), any());
            verify(f.snapshots, never()).saveIfAbsent(any(), any());
            for (Player p : players) assertTrue(f.service.activeSessionContains(p.getUniqueId()));
            call(f.service, "tick");
            verify(f.snapshots, times(4)).claimLobbySnapshot(any(), any());
        }
    }

    @Test void nextGameStartsAfterExactlySevenSecondsIncludingItsCountdown() throws Exception {
        try (var bukkit = mockStatic(Bukkit.class)) {
            Fixture f = new Fixture();
            when(f.config.global().roundResultsSeconds()).thenReturn(2);
            when(f.config.global().intermissionSeconds()).thenReturn(0);
            when(f.config.global().roundCountdownSeconds()).thenReturn(5);
            var games = f.games();
            var session = new MinigamesSession(UUID.randomUUID(), SessionMode.ADMIN_TEST, null, Set.of(), games);
            var first = mock(Minigame.class); when(first.finish(any(), any())).thenReturn(RoundResult.empty());
            var next = mock(Minigame.class); when(next.countdownSeconds(any(), anyInt())).thenReturn(20);
            when(next.startDelayTicks()).thenReturn(100);
            when(f.registry.create(games.get(1).id())).thenReturn(Optional.of(next));
            var round = new RoundSession(1, session.consumeNextGame(), first, Set.of(), 100);
            session.currentRound(round); set(f.service, "activeSession", session);
            var finish = MinigamesSessionService.class.getDeclaredMethod("finishRound", MinigamesSession.class, RoundSession.class, RoundEndReason.class);
            finish.setAccessible(true); finish.invoke(f.service, session, round, RoundEndReason.TIME_LIMIT);
            for (int tick = 0; tick < 139; tick++) call(f.service, "tick");
            verify(next, never()).start(any());
            call(f.service, "tick");
            verify(next).start(any());
            assertEquals(0, session.currentRound().startDelayRemaining());
        }
    }

    private static final class Fixture {
        final Plugin plugin = mock(Plugin.class);
        final PlayerSnapshotRepository snapshots = mock(PlayerSnapshotRepository.class);
        final ScoreService scores = mock(ScoreService.class);
        final MinigameRegistry registry = mock(MinigameRegistry.class);
        final LoadedMinigamesConfig config = mock(LoadedMinigamesConfig.class, RETURNS_DEEP_STUBS);
        final MinigamesSessionService service = new MinigamesSessionService(plugin, snapshots, scores, registry);
        Fixture() throws Exception {
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            when(config.messages()).thenReturn(new Messages(Map.of()));
            when(config.global().worldName()).thenReturn("Hex_Minigames");
            when(config.global().maxPlayers()).thenReturn(14);
            when(config.global().gamesPerSeries()).thenReturn(5);
            when(config.global().seriesResultsSeconds()).thenReturn(12);
            when(config.global().pregame().spawn()).thenReturn(new LocationSpec(570,-29,86,0,0,true));
            set(service, "config", config);
        }
        List<MinigameDefinition> games() {
            List<MinigameDefinition> games = new ArrayList<>();
            for (int i = 0; i < 5; i++) games.add(new MinigameDefinition("g" + i, "Game",true,true,false,1,14,1,
                    Optional.empty(), List.of(), Optional.empty(),60,Map.of(),"test"));
            return games;
        }
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void call(Object target, String name, MinigamesSession... session) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, session.length == 0 ? new Class<?>[0] : new Class<?>[]{MinigamesSession.class});
        method.setAccessible(true); method.invoke(target, (Object[]) session);
    }
}
