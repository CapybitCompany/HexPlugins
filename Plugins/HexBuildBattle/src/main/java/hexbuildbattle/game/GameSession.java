package hexbuildbattle.game;

import hexbuildbattle.arena.Arena;
import hexbuildbattle.score.RoundBuildScore;
import hexbuildbattle.theme.Theme;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class GameSession {

    private GameState state = GameState.WAITING;
    private final LinkedHashSet<UUID> participants = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> activeParticipants = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> eligibleBuildOwners = new LinkedHashSet<>();
    private final Map<UUID, Arena> arenaAssignments = new LinkedHashMap<>();
    private final Map<UUID, RoundBuildScore> scores = new LinkedHashMap<>();
    private Theme selectedTheme;
    private int phaseRemainingSeconds;
    private UUID currentJudgedOwner;

    public GameState state() {
        return state;
    }

    void state(GameState state) {
        this.state = state;
    }

    public int countdownRemainingSeconds() {
        return phaseRemainingSeconds;
    }

    public void countdownRemainingSeconds(int countdownRemainingSeconds) {
        this.phaseRemainingSeconds = countdownRemainingSeconds;
    }

    public int phaseRemainingSeconds() {
        return phaseRemainingSeconds;
    }

    public void phaseRemainingSeconds(int phaseRemainingSeconds) {
        this.phaseRemainingSeconds = Math.max(0, phaseRemainingSeconds);
    }

    public void startRound(List<UUID> playerIds, Map<UUID, Arena> assignments, Map<UUID, String> names) {
        clearRound();
        participants.addAll(playerIds);
        activeParticipants.addAll(playerIds);
        eligibleBuildOwners.addAll(playerIds);
        arenaAssignments.putAll(assignments);
        for (UUID playerId : playerIds) {
            Arena arena = assignments.get(playerId);
            if (arena != null) {
                scores.put(playerId, new RoundBuildScore(playerId, names.getOrDefault(playerId, "Unknown"), arena));
            }
        }
    }

    public boolean isParticipant(UUID playerId) {
        return participants.contains(playerId);
    }

    public boolean isActiveParticipant(UUID playerId) {
        return activeParticipants.contains(playerId);
    }

    public boolean removeActiveParticipant(UUID playerId, boolean keepBuildEligible) {
        boolean removed = activeParticipants.remove(playerId);
        if (!keepBuildEligible) {
            eligibleBuildOwners.remove(playerId);
            scores.remove(playerId);
        }
        return removed;
    }

    public int participantCount() {
        return activeParticipants.size();
    }

    public Set<UUID> participants() {
        return Collections.unmodifiableSet(participants);
    }

    public Set<UUID> activeParticipants() {
        return Collections.unmodifiableSet(activeParticipants);
    }

    public Collection<Arena> usedArenas() {
        return List.copyOf(arenaAssignments.values());
    }

    public Optional<Arena> assignedArena(UUID playerId) {
        return Optional.ofNullable(arenaAssignments.get(playerId));
    }

    public Optional<RoundBuildScore> score(UUID ownerId) {
        return Optional.ofNullable(scores.get(ownerId));
    }

    public List<RoundBuildScore> eligibleScores() {
        List<RoundBuildScore> result = new ArrayList<>();
        for (UUID ownerId : eligibleBuildOwners) {
            RoundBuildScore score = scores.get(ownerId);
            if (score != null) {
                result.add(score);
            }
        }
        return result;
    }

    public boolean isBuildEligibleForJudging(UUID ownerId) {
        return eligibleBuildOwners.contains(ownerId) && scores.containsKey(ownerId);
    }

    public List<UUID> eligibleBuildOwners() {
        return List.copyOf(eligibleBuildOwners);
    }

    public Theme selectedTheme() {
        return selectedTheme;
    }

    public void selectedTheme(Theme selectedTheme) {
        this.selectedTheme = selectedTheme;
    }

    public UUID currentJudgedOwner() {
        return currentJudgedOwner;
    }

    public void currentJudgedOwner(UUID currentJudgedOwner) {
        this.currentJudgedOwner = currentJudgedOwner;
    }

    public void clearRound() {
        participants.clear();
        activeParticipants.clear();
        eligibleBuildOwners.clear();
        arenaAssignments.clear();
        scores.clear();
        selectedTheme = null;
        currentJudgedOwner = null;
        phaseRemainingSeconds = 0;
    }
}
