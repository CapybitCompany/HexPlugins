package hex.minigames.game;

import hex.minigames.runtime.RoundPlayerState;
import hex.minigames.runtime.RoundSession;
import hex.minigames.runtime.MinigamesSessionService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class RoundContext {
    private final MinigamesSessionService sessions;
    private final RoundSession round;

    public RoundContext(MinigamesSessionService sessions, RoundSession round) {
        this.sessions = sessions;
        this.round = round;
    }

    public MinigameDefinition definition() {
        return round.definition();
    }

    public Set<UUID> participants() {
        return round.participants();
    }

    public List<Player> onlineParticipants() {
        return participants().stream()
                .map(Bukkit::getPlayer)
                .filter(player -> player != null && player.isOnline())
                .toList();
    }

    public Optional<Player> player(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player == null || !player.isOnline() ? Optional.empty() : Optional.of(player);
    }

    public RoundPlayerState state(UUID playerId) {
        return round.playerState(playerId);
    }

    public void state(UUID playerId, RoundPlayerState state) {
        sessions.setRoundPlayerState(round, playerId, state);
    }

    public void blockChat(UUID playerId) {
        sessions.blockChat(playerId);
    }

    public void unblockChat(UUID playerId) {
        sessions.unblockChat(playerId);
    }

    public void requestFinish(RoundEndReason reason) {
        sessions.requestRoundFinish(round, reason);
    }

    public long elapsedTicks() {
        return round.elapsedTicks();
    }

    public int startDelayRemaining() { return round.startDelayRemaining(); }

    /** Moves a player after the current movement/damage event has finished processing. */
    public void respawn(Player player, org.bukkit.Location target, Runnable afterArrival) {
        sessions.respawnRoundPlayer(round, player, target, afterArrival);
    }
}
