package hex.minigames.runtime;

import hex.minigames.config.ConfiguredSound;
import hex.minigames.config.GlobalConfig;
import hex.minigames.config.LoadedMinigamesConfig;
import hex.minigames.config.Messages;
import hex.minigames.config.PregameConfig;
import hex.minigames.config.SeriesConfig;
import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.MinigameRegistry;
import hex.minigames.model.LocationSpec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinigamesSessionRulesTest {
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

    private MinigamesSession session(int players) {
        Set<UUID> participants = new LinkedHashSet<>();
        for (int i = 0; i < players; i++) {
            participants.add(new UUID(0L, i + 1L));
        }
        return new MinigamesSession(UUID.randomUUID(), SessionMode.EVENT, null, participants, List.of());
    }

    private LoadedMinigamesConfig config() {
        PregameConfig pregame = new PregameConfig(true, null, LocationSpec.missing(), 5, 10, 10);
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
