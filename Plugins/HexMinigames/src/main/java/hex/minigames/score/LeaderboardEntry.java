package hex.minigames.score;
import java.util.UUID;
/** Immutable historical score used by the placeholder cache. */
public record LeaderboardEntry(UUID playerId, String name, int points) { }
