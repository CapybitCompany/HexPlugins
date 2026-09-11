package hex.minigames.score;

import hex.minigames.game.RoundResult;
import hex.minigames.runtime.MinigamesSession;
import hex.minigames.runtime.RoundSession;
import hex.minigames.runtime.SessionMode;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;

public final class ScoreService {
    private final Plugin plugin;
    private final MinigamesScoreRepository repository;

    public ScoreService(Plugin plugin, MinigamesScoreRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    public String backendName() {
        return repository.backendName();
    }

    public boolean available() {
        return repository.available();
    }

    public void applyRoundResult(MinigamesSession session, RoundSession round, RoundResult result) {
        if (!round.markPointsApplied()) return;
        for (Map.Entry<UUID, hex.minigames.game.PlayerRoundResult> entry : result.players().entrySet()) {
            UUID playerId = entry.getKey();
            if (!session.contains(playerId)) continue;
            if (session.forfeited(playerId)) continue;
            int points = entry.getValue().points();
            session.seriesScore().add(playerId, points);
        }
    }

    public void commitEligibleSeries(MinigamesSession session) {
        if (session.mode() != SessionMode.EVENT) return;
        for (UUID playerId : session.participants()) {
            if (session.forfeited(playerId)) continue;
            if (!session.markGlobalCommitted(playerId)) continue;
            int points = session.seriesScore().points(playerId);
            try {
                repository.commitSeriesPoints(session.instanceId(), playerId, points);
            } catch (Throwable error) {
                plugin.getLogger().warning("Could not commit minigames series points for " + playerId + ": " + rootMessage(error));
            }
        }
    }

    public int getSeriesPoints(MinigamesSession session, UUID playerId) {
        return session.seriesScore().points(playerId);
    }

    public int getGlobalPoints(UUID playerId) {
        return repository.globalPoints(playerId);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
