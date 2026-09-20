package hex.minigames.score;

import hex.minigames.game.RoundResult;
import hex.minigames.runtime.*;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.*;

/** Serial database work and immutable cached reads; placeholders never query SQL. */
public final class ScoreService implements AutoCloseable {
    private final Plugin plugin;
    private final MinigamesScoreRepository repository;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "HexMinigames-scores"); thread.setDaemon(true); return thread;
    });
    private volatile Map<UUID, LeaderboardEntry> cache = Map.of();
    private volatile List<LeaderboardEntry> ranking = List.of();
    public ScoreService(Plugin plugin, MinigamesScoreRepository repository) {
        this.plugin = plugin; this.repository = repository;
        refresh().exceptionally(error -> { log(error); return null; });
    }
    public String backendName() { return repository.backendName(); }
    public boolean available() { return repository.available(); }
    public void applyRoundResult(MinigamesSession session, RoundSession round, RoundResult result) {
        if (!round.markPointsApplied()) return;
        result.players().forEach((id, outcome) -> {
            if (session.contains(id) && !session.forfeited(id)) session.seriesScore().add(id, outcome.points());
        });
    }
    public CompletableFuture<Void> commitEligibleSeries(MinigamesSession session) {
        if (session.mode() != SessionMode.EVENT) return CompletableFuture.completedFuture(null);
        Map<UUID, Integer> points = new LinkedHashMap<>();
        Map<UUID, String> names = new HashMap<>();
        for (UUID id : session.participants()) {
            if (!session.forfeited(id)) { points.put(id, session.seriesScore().points(id)); names.put(id, session.playerName(id)); }
        }
        return CompletableFuture.runAsync(() -> {
            points.forEach((id, amount) -> {
                repository.saveName(id, names.get(id));
                repository.commitSeriesPoints(session.instanceId(), id, amount);
            });
            reloadCache();
        }, worker).whenComplete((unused, error) -> { if (error != null) log(error); });
    }
    public int getSeriesPoints(MinigamesSession session, UUID id) { return session.seriesScore().points(id); }
    public int getGlobalPoints(UUID id) { var entry = cache.get(id); return entry == null ? 0 : entry.points(); }
    public List<LeaderboardEntry> top(int count) { return ranking.stream().limit(Math.max(0, count)).toList(); }
    public CompletableFuture<Void> refresh() { return CompletableFuture.runAsync(this::reloadCache, worker); }
    public CompletableFuture<Void> resetAll() {
        return CompletableFuture.runAsync(() -> { repository.resetScores(); reloadCache(); }, worker);
    }
    private void reloadCache() {
        Map<UUID, LeaderboardEntry> loaded = new HashMap<>();
        for (LeaderboardEntry entry : repository.allScores()) loaded.put(entry.playerId(), entry);
        cache = Map.copyOf(loaded);
        ranking = loaded.values().stream().sorted(Comparator.comparingInt(LeaderboardEntry::points).reversed()
                .thenComparing(entry -> entry.playerId().toString())).toList();
    }
    private void log(Throwable error) {
        if (plugin != null) plugin.getLogger().log(java.util.logging.Level.SEVERE, "Minigames score storage operation failed", error);
    }
    @Override public void close() {
        worker.shutdown();
        try { if (!worker.awaitTermination(5, TimeUnit.SECONDS)) log(new IllegalStateException("Score writes are still pending during shutdown")); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
