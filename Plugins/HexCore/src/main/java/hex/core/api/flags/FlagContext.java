package hex.core.api.flags;

import java.util.UUID;

/**
 * Represents execution context used for resolving feature flags.
 * Allows overrides per game, arena or player.
 */
public record FlagContext(String gameId, String arenaId, UUID playerId) {
    /** Creates a global (no-context) flag scope. */
    public static FlagContext global() { return new FlagContext(null, null, null); }
    public FlagContext withGame(String gameId) { return new FlagContext(gameId, arenaId, playerId); }
    public FlagContext withArena(String arenaId) { return new FlagContext(gameId, arenaId, playerId); }
    public FlagContext withPlayer(UUID playerId) { return new FlagContext(gameId, arenaId, playerId); }
}
