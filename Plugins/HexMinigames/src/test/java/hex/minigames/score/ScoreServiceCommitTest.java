package hex.minigames.score;

import hex.minigames.runtime.MinigamesSession;
import hex.minigames.runtime.SessionMode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ScoreServiceCommitTest {
    @Test
    void forfeitedPlayerDoesNotCommitSeriesPoints() {
        UUID player = UUID.randomUUID();
        FakeScoreRepository repository = new FakeScoreRepository(Map.of(player, 100));
        ScoreService scores = new ScoreService(null, repository);
        MinigamesSession session = session(player);
        session.seriesScore().add(player, 10);
        session.forfeitParticipant(player);

        scores.commitEligibleSeries(session);

        assertEquals(100, repository.globalPoints(player));
    }

    @Test
    void finishingPlayerCommitsSeriesPoints() {
        UUID player = UUID.randomUUID();
        FakeScoreRepository repository = new FakeScoreRepository(Map.of(player, 100));
        ScoreService scores = new ScoreService(null, repository);
        MinigamesSession session = session(player);
        session.seriesScore().add(player, 10);

        scores.commitEligibleSeries(session);

        assertEquals(110, repository.globalPoints(player));
    }

    @Test
    void developmentTestDoesNotCommitSeriesPoints() {
        UUID player = UUID.randomUUID();
        FakeScoreRepository repository = new FakeScoreRepository(Map.of(player, 100));
        ScoreService scores = new ScoreService(null, repository);
        MinigamesSession session = session(SessionMode.DEVELOPMENT_TEST, player);
        session.seriesScore().add(player, 10);

        scores.commitEligibleSeries(session);

        assertEquals(100, repository.globalPoints(player));
    }

    @Test
    void duplicateCleanupDoesNotCommitTwice() {
        UUID player = UUID.randomUUID();
        FakeScoreRepository repository = new FakeScoreRepository(Map.of(player, 100));
        ScoreService scores = new ScoreService(null, repository);
        MinigamesSession session = session(player);
        session.seriesScore().add(player, 10);

        scores.commitEligibleSeries(session);
        scores.commitEligibleSeries(session);

        assertEquals(110, repository.globalPoints(player));
    }

    @Test
    void emergencyFinishCommitsRemainingPlayerOnly() {
        UUID remaining = UUID.randomUUID();
        UUID forfeited = UUID.randomUUID();
        FakeScoreRepository repository = new FakeScoreRepository(Map.of(remaining, 100, forfeited, 100));
        ScoreService scores = new ScoreService(null, repository);
        MinigamesSession session = session(remaining, forfeited);
        session.seriesScore().add(remaining, 12);
        session.seriesScore().add(forfeited, 8);
        session.forfeitParticipant(forfeited);

        scores.commitEligibleSeries(session);

        assertEquals(112, repository.globalPoints(remaining));
        assertEquals(100, repository.globalPoints(forfeited));
    }

    private MinigamesSession session(UUID... players) {
        return new MinigamesSession(UUID.randomUUID(), SessionMode.EVENT, null, new LinkedHashSet<>(List.of(players)), List.of());
    }

    private MinigamesSession session(SessionMode mode, UUID... players) {
        return new MinigamesSession(UUID.randomUUID(), mode, null, new LinkedHashSet<>(List.of(players)), List.of());
    }

    private static final class FakeScoreRepository implements MinigamesScoreRepository {
        private final Map<UUID, Integer> global = new HashMap<>();
        private final Set<String> commits = new HashSet<>();

        private FakeScoreRepository(Map<UUID, Integer> initial) {
            global.putAll(initial);
        }

        @Override
        public void initialize() {
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String backendName() {
            return "fake";
        }

        @Override
        public boolean commitSeriesPoints(UUID sessionId, UUID playerId, int points) {
            if (!commits.add(sessionId + ":" + playerId)) return false;
            global.put(playerId, global.getOrDefault(playerId, 0) + points);
            return true;
        }

        @Override
        public int globalPoints(UUID playerId) {
            return global.getOrDefault(playerId, 0);
        }
    }
}
