package hex.minigames.runtime;

import hex.minigames.config.ConfiguredSound;
import hex.minigames.config.GlobalConfig;
import hex.minigames.config.LoadedMinigamesConfig;
import hex.minigames.config.Messages;
import hex.minigames.config.PregameConfig;
import hex.minigames.config.SeriesConfig;
import hex.minigames.game.Minigame;
import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.MinigameFactory;
import hex.minigames.game.MinigameRegistry;
import hex.minigames.game.PlayerRoundResult;
import hex.minigames.game.RoundResult;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinigamesSessionRulesTest {
    @Test
    void timedGamesContinueWithNoActivePlayersUntilTheirTimeLimit() throws Exception {
        for (Minigame game : List.of(new hex.minigames.game.hothead.HotHeadMinigame(null),
                new hex.minigames.game.glassbridge.GlassBridgeMinigame(null))) {
            MinigamesSession session = session(2);
            session.state(SeriesState.ROUND_RUNNING);
            RoundSession round = new RoundSession(1, definition(game.id()), game, session.participants(), 100);
            session.currentRound(round);
            while (round.startDelayRemaining() > 0) round.tickStartDelay();
            for (UUID player : session.participants()) round.playerState(player, RoundPlayerState.GHOST);
            MinigamesSessionService service = new MinigamesSessionService(null, null, null, new MinigameRegistry());
            Field field = MinigamesSessionService.class.getDeclaredField("activeSession");
            field.setAccessible(true);
            field.set(service, session);
            Method tick = MinigamesSessionService.class.getDeclaredMethod("tickRunning", MinigamesSession.class);
            tick.setAccessible(true);
            for (int i = 0; i < 99; i++) tick.invoke(service, session);
            assertEquals(SeriesState.ROUND_RUNNING, session.state());
            assertFalse(round.finishRequested());
            assertFalse(round.timeLimitReached());
            round.tickElapsed();
            assertTrue(round.timeLimitReached());
        }
    }

    @Test
    void onlyHotHeadBlocksHealing() {
        assertEquals(hex.minigames.game.EventDecision.DENY,
                new hex.minigames.game.hothead.HotHeadMinigame(null).onRegainHealth(null, null));
        assertEquals(hex.minigames.game.EventDecision.PASS,
                new hex.minigames.game.popcorn.PopcornMinigame(null).onRegainHealth(null, null));
    }

    @Test
    void fivePlayersCanBeginPregame() {
        MinigamesSession session = session(5);

        assertTrue(session.canBeginPregame(5));
    }

    @Test
    void countdownCancelsWhenFiveDropsToFour() {
        MinigamesSession session = session(5);
        session.state(SeriesState.PRE_GAME_COUNTDOWN);
        session.forfeitParticipant(session.participants().iterator().next());

        assertTrue(session.shouldAbortPregame(5));
    }

    @Test
    void countdownContinuesWhenSixDropsToFive() {
        MinigamesSession session = session(6);
        session.state(SeriesState.PRE_GAME_COUNTDOWN);
        session.forfeitParticipant(session.participants().iterator().next());

        assertFalse(session.shouldAbortPregame(5));
    }

    @Test
    void activeSeriesContinuesWhenThreeDropsToTwo() {
        MinigamesSession session = session(3);
        session.state(SeriesState.ROUND_RUNNING);
        session.markSeriesStarted();
        session.forfeitParticipant(session.participants().iterator().next());

        assertFalse(session.shouldFinishForMinimumContinuation(2));
    }

    @Test
    void activeSeriesEndsWhenTwoDropsToOne() {
        MinigamesSession session = session(2);
        session.state(SeriesState.ROUND_RUNNING);
        session.markSeriesStarted();
        session.forfeitParticipant(session.participants().iterator().next());

        assertTrue(session.shouldFinishForMinimumContinuation(2));
    }

    @Test
    void developmentTestModeHasExplicitStatusLabel() {
        assertEquals("TEST/DEVELOPMENT", SessionMode.DEVELOPMENT_TEST.statusLabel());
    }

    @Test
    void fourteenthPlayerFitsButFifteenthIsRejectedBySessionCapacity() {
        MinigamesSession session = session(14);

        assertFalse(session.canAcceptParticipant(14));
    }

    @Test
    void pregameDrawTransitionsToPregameCountdown() throws Exception {
        MinigameDefinition selected = new MinigameDefinition("super_memory", "SuperMemory", true, true, false, 1, 14, 1, java.util.Optional.empty(), List.of(new LocationSpec(0, 0, 0, 0, 0, true)), java.util.Optional.of(new LocationSpec(0, 0, 0, 0, 0, true)), 90, Map.of(), "test");
        MinigamesSession session = new MinigamesSession(UUID.randomUUID(), SessionMode.DEVELOPMENT_TEST, null, new LinkedHashSet<>(), List.of(selected));
        session.state(SeriesState.PRE_GAME_DRAW);
        session.stateTicksRemaining(0);

        MinigamesSessionService service = new MinigamesSessionService(null, null, null, new MinigameRegistry());
        service.configure(config());
        Field activeSession = MinigamesSessionService.class.getDeclaredField("activeSession");
        activeSession.setAccessible(true);
        activeSession.set(service, session);
        Method freeze = MinigamesSessionService.class.getDeclaredMethod("freezeSelectedGamesAfterDraw", MinigamesSession.class);
        freeze.setAccessible(true);

        freeze.invoke(service, session);

        assertEquals(SeriesState.PRE_GAME_COUNTDOWN, session.state());
    }

    @Test
    void pregameDrawTextShowsFiveOfThirteenPlannedGames() {
        String message = MinigamesSessionService.pregameDrawActionbarMessage(new Messages(Map.of()), 5, 13);

        assertEquals("&7Trwa losowanie &e5 &7z &e13 &7minigier...", message);
    }

    @Test
    void pregameCountdownSoundPlaysForEveryValueFromTenToZero() {
        PregameConfig pregame = new PregameConfig(
                true,
                null,
                LocationSpec.missing(),
                5,
                10,
                10,
                "&d&lPOCZEKALNIA",
                BarColor.WHITE,
                BarStyle.SOLID,
                new ConfiguredSound(true, "UI_BUTTON_CLICK", 0.8f, 1.4f)
        );

        for (int second = 10; second >= 0; second--) {
            assertTrue(MinigamesSessionService.shouldPlayPregameCountdownSound(second, pregame));
        }
        assertFalse(MinigamesSessionService.shouldPlayPregameCountdownSound(11, pregame));
        assertFalse(MinigamesSessionService.shouldPlayPregameCountdownSound(-1, pregame));
    }

    @Test
    void roundEndSubtitleDefaultsToKoniec() {
        assertEquals("&6KONIEC", MinigamesSessionService.roundEndSubtitle(new Messages(Map.of())));
    }

    @Test
    void roundRankingUsesRoundPointsAndPendingSeriesScore() {
        UUID quezo = new UUID(0L, 1L);
        UUID nick2 = new UUID(0L, 2L);
        RoundResult result = new RoundResult(Map.of(
                quezo, new PlayerRoundResult(3, OptionalInt.empty(), true, false, Map.of()),
                nick2, new PlayerRoundResult(2, OptionalInt.empty(), true, false, Map.of())
        ), Map.of());

        List<String> lines = MinigamesSessionService.roundRankingLines(
                result,
                Map.of(quezo, 7, nick2, 5),
                Map.of(quezo, "Quezo", nick2, "Nick2"),
                "&d&lWYNIKI"
        );

        assertEquals("&d&lWYNIKI", lines.get(0));
        assertEquals("&f1. &eQuezo &7+3 pkt &8| &fSuma: &d7 pkt", lines.get(1));
        assertEquals("&f2. &eNick2 &7+2 pkt &8| &fSuma: &d5 pkt", lines.get(2));
    }

    @Test
    void pregameBossbarDefaultsToPinkBoldAndWhiteBar() {
        PregameConfig pregame = config().global().pregame();

        assertEquals("&d&lPOCZEKALNIA", pregame.bossBarTitle());
        assertEquals(BarColor.WHITE, pregame.bossBarColor());
    }

    @Test
    void developmentSingleGameSelectionUsesRequestedGameWithoutRandomDraw() throws Exception {
        MinigameDefinition requested = definition("hot_head");
        MinigameRegistry registry = new MinigameRegistry();
        registry.register(factory("hot_head"));
        registry.register(factory("popcorn"));
        registry.rebuild(Map.of("hot_head", requested, "popcorn", definition("popcorn")));
        MinigamesSession session = new MinigamesSession(UUID.randomUUID(), SessionMode.DEVELOPMENT_TEST, null, new LinkedHashSet<>(List.of(UUID.randomUUID())), List.of());
        session.state(SeriesState.PRE_GAME_WAITING);

        MinigamesSessionService service = new MinigamesSessionService(null, null, null, registry);
        service.configure(config());
        Field activeSession = MinigamesSessionService.class.getDeclaredField("activeSession");
        activeSession.setAccessible(true);
        activeSession.set(service, session);

        String result = service.selectDevelopmentGame("hot_head");

        assertTrue(result.contains("hot_head"));
        assertEquals(List.of("hot_head"), session.selectedGames().stream().map(MinigameDefinition::id).toList());
    }

    private MinigamesSession session(int players) {
        Set<UUID> participants = new LinkedHashSet<>();
        for (int i = 0; i < players; i++) {
            participants.add(new UUID(0L, i + 1L));
        }
        return new MinigamesSession(UUID.randomUUID(), SessionMode.EVENT, null, participants, List.of());
    }

    private MinigameDefinition definition(String id) {
        return new MinigameDefinition(
                id,
                id,
                true,
                true,
                false,
                1,
                14,
                1,
                java.util.Optional.of(new CuboidRegion("Hex_Minigames", new BlockPosition(0, 0, 0), new BlockPosition(10, 10, 10))),
                List.of(new LocationSpec(0, 0, 0, 0, 0, true)),
                java.util.Optional.of(new LocationSpec(0, 0, 0, 0, 0, true)),
                90,
                Map.of(),
                "test"
        );
    }

    private MinigameFactory factory(String id) {
        return new MinigameFactory() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean internal() {
                return false;
            }

            @Override
            public Minigame create() {
                return () -> id;
            }
        };
    }

    private LoadedMinigamesConfig config() {
        PregameConfig pregame = new PregameConfig(
                true,
                null,
                LocationSpec.missing(),
                5,
                10,
                10,
                "&d&lPOCZEKALNIA",
                BarColor.WHITE,
                BarStyle.SOLID,
                new ConfiguredSound(true, "UI_BUTTON_CLICK", 0.8f, 1.4f)
        );
        GlobalConfig global = new GlobalConfig(
                "Hex_Minigames",
                14,
                2,
                5,
                5,
                90,
                10,
                8,
                12,
                "sqlite",
                "test.db",
                true,
                true,
                pregame,
                new SeriesConfig(2),
                true,
                false,
                10,
                LocationSpec.missing(),
                new ConfiguredSound(false, "", 1.0f, 1.0f),
                List.of()
        );
        return new LoadedMinigamesConfig(global, new Messages(Map.of()), Map.of(), List.of());
    }
}
