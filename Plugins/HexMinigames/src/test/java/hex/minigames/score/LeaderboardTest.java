package hex.minigames.score;
import hex.minigames.runtime.*;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class LeaderboardTest {
    @Test void commitResetAndRestartKeepCacheAndPlaceholdersConsistent() {
        var repository = new MemoryRepository();
        UUID id = UUID.randomUUID();
        var session = new MinigamesSession(UUID.randomUUID(), SessionMode.EVENT, null, Set.of(id), List.of());
        session.rememberName(id, "Winner"); session.seriesScore().add(id, 15);
        try (var scores = new ScoreService(null, repository)) {
            scores.commitEligibleSeries(session).join();
            scores.commitEligibleSeries(session).join();
            assertEquals(15, scores.getGlobalPoints(id));
            assertEquals("Winner", scores.top(5).getFirst().name());
            var expansion = new MinigamesPlaceholderExpansion(null, scores);
            var player = mock(OfflinePlayer.class); when(player.getUniqueId()).thenReturn(id);
            assertEquals("15", expansion.onRequest(player, "points"));
            assertEquals("Winner - 15", expansion.onRequest(null, "top1"));
            assertEquals("Winner", expansion.onRequest(null, "top_1_name"));
            assertEquals("15", expansion.onRequest(null, "top_1_points"));
            assertEquals("-", expansion.onRequest(null, "top5"));
            assertNull(expansion.onRequest(null, "unknown"));
            scores.resetAll().join();
            assertEquals("0", expansion.onRequest(player, "points"));
            assertEquals("-", expansion.onRequest(null, "top1"));
            scores.commitEligibleSeries(session).join();
            assertEquals(0, scores.getGlobalPoints(id)); // Reset must not allow old awards to replay.
        }
        var next = new MinigamesSession(UUID.randomUUID(), SessionMode.EVENT, null, Set.of(id), List.of());
        next.rememberName(id, "Winner"); next.seriesScore().add(id, 7);
        try (var scores = new ScoreService(null, repository)) { scores.commitEligibleSeries(next).join(); }
        try (var scores = new ScoreService(null, repository)) {
            scores.refresh().join(); assertEquals(7, scores.getGlobalPoints(id));
        }
    }
    @Test void allFourteenPlayersAppearIncludingZeroPointsAndTiesAreStable() {
        Set<UUID> ids = new LinkedHashSet<>(); for (int i = 0; i < 14; i++) ids.add(UUID.randomUUID());
        var session = new MinigamesSession(UUID.randomUUID(), SessionMode.EVENT, null, ids, List.of());
        int index = 0;
        for (UUID id : ids) { session.rememberName(id, String.format("Player%02d", index)); session.seriesScore().add(id, index++ / 2); }
        var ranked = session.ranking();
        assertEquals(14, ranked.size());
        assertEquals("Player12", session.playerName(ranked.getFirst()));
        assertEquals("Player13", session.playerName(ranked.get(1)));
        session.forfeitParticipant(ranked.get(1));
        assertEquals(14, session.ranking().size());
        assertEquals(0, session.seriesScore().points(session.ranking().getLast()));
    }
    @Test void queuedResetRunsAfterPendingWrites() {
        var repository = new MemoryRepository();
        UUID id = UUID.randomUUID();
        var session = new MinigamesSession(UUID.randomUUID(), SessionMode.EVENT, null, Set.of(id), List.of());
        session.seriesScore().add(id, 10);
        try (var scores = new ScoreService(null, repository)) {
            scores.commitEligibleSeries(session);
            scores.resetAll().join();
            assertEquals(0, scores.getGlobalPoints(id));
            assertTrue(scores.top(5).isEmpty());
        }
    }
    private static final class MemoryRepository implements MinigamesScoreRepository {
        final Map<UUID, Integer> points = new ConcurrentHashMap<>();
        final Map<UUID, String> names = new ConcurrentHashMap<>();
        final Set<String> commits = ConcurrentHashMap.newKeySet();
        public void initialize() { }
        public boolean available() { return true; }
        public String backendName() { return "test"; }
        public boolean commitSeriesPoints(UUID session, UUID player, int amount) {
            if (!commits.add(session + ":" + player)) return false;
            points.merge(player, amount, Integer::sum); return true;
        }
        public int globalPoints(UUID id) { return points.getOrDefault(id, 0); }
        public List<LeaderboardEntry> allScores() { return points.entrySet().stream().map(e -> new LeaderboardEntry(e.getKey(), names.get(e.getKey()), e.getValue())).toList(); }
        public void saveName(UUID id, String name) { names.put(id, name); }
        public void resetScores() { points.clear(); }
    }
}
