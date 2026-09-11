package hex.minigames.runtime;

import hex.minigames.game.Minigame;
import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.RoundEndReason;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RoundSession {
    private final int roundNumber;
    private final MinigameDefinition definition;
    private final Minigame minigame;
    private final Set<UUID> participants;
    private final Map<UUID, RoundPlayerState> playerStates = new LinkedHashMap<>();
    private final int maxTicks;
    private long elapsedTicks;
    private boolean started;
    private boolean finishRequested;
    private RoundEndReason requestedReason = RoundEndReason.MINIGAME_REQUEST;
    private boolean pointsApplied;
    private boolean cleanedUp;

    public RoundSession(int roundNumber, MinigameDefinition definition, Minigame minigame, Set<UUID> participants, int maxTicks) {
        this.roundNumber = roundNumber;
        this.definition = definition;
        this.minigame = minigame;
        this.participants = new LinkedHashSet<>(participants);
        this.maxTicks = Math.max(1, maxTicks);
        for (UUID participant : participants) {
            playerStates.put(participant, RoundPlayerState.ACTIVE);
        }
    }

    public int roundNumber() {
        return roundNumber;
    }

    public MinigameDefinition definition() {
        return definition;
    }

    public Minigame minigame() {
        return minigame;
    }

    public Set<UUID> participants() {
        return Set.copyOf(participants);
    }

    public RoundPlayerState playerState(UUID playerId) {
        return playerStates.getOrDefault(playerId, RoundPlayerState.ELIMINATED);
    }

    public void playerState(UUID playerId, RoundPlayerState state) {
        if (!playerStates.containsKey(playerId)) return;
        playerStates.put(playerId, state);
    }

    public void removePlayer(UUID playerId) {
        participants.remove(playerId);
        playerStates.remove(playerId);
    }

    public int activeCount() {
        int count = 0;
        for (RoundPlayerState state : playerStates.values()) {
            if (state == RoundPlayerState.ACTIVE) count++;
        }
        return count;
    }

    public int participantCount() {
        return participants.size();
    }

    public boolean allActiveResolved() {
        return !participants.isEmpty() && activeCount() == 0;
    }

    public void tickElapsed() {
        elapsedTicks += 1L;
    }

    public long elapsedTicks() {
        return elapsedTicks;
    }

    public boolean timeLimitReached() {
        return elapsedTicks >= maxTicks;
    }

    public boolean started() {
        return started;
    }

    public void started(boolean started) {
        this.started = started;
    }

    public void requestFinish(RoundEndReason reason) {
        if (finishRequested) return;
        finishRequested = true;
        requestedReason = reason == null ? RoundEndReason.MINIGAME_REQUEST : reason;
    }

    public boolean finishRequested() {
        return finishRequested;
    }

    public RoundEndReason requestedReason() {
        return requestedReason;
    }

    public boolean markPointsApplied() {
        if (pointsApplied) return false;
        pointsApplied = true;
        return true;
    }

    public boolean markCleanedUp() {
        if (cleanedUp) return false;
        cleanedUp = true;
        return true;
    }
}
