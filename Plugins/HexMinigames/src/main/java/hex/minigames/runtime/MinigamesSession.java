package hex.minigames.runtime;

import hex.events.api.EventExecutionContext;
import hex.minigames.game.MinigameDefinition;
import hex.minigames.score.SeriesScore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MinigamesSession {
    private final UUID instanceId;
    private final SessionMode mode;
    private final EventExecutionContext eventContext;
    private final Set<UUID> participants;
    private final Set<UUID> forfeitedParticipants = new LinkedHashSet<>();
    private final Set<UUID> committedParticipants = new LinkedHashSet<>();
    private final List<MinigameDefinition> selectedGames;
    private final SeriesScore seriesScore = new SeriesScore();
    private final Map<UUID, Integer> globalScoreAtSeriesStart;
    private SeriesState state = SeriesState.WAITING;
    private int stateTicksRemaining;
    private int nextRoundIndex;
    private RoundSession currentRound;
    private MinigameDefinition forcedNextGame;
    private boolean terminalNotified;
    private boolean seriesStarted;

    public MinigamesSession(UUID instanceId, SessionMode mode, EventExecutionContext eventContext, Set<UUID> participants, List<MinigameDefinition> selectedGames) {
        this(instanceId, mode, eventContext, participants, selectedGames, Map.of());
    }

    public MinigamesSession(UUID instanceId, SessionMode mode, EventExecutionContext eventContext, Set<UUID> participants, List<MinigameDefinition> selectedGames, Map<UUID, Integer> globalScoreAtSeriesStart) {
        this.instanceId = instanceId;
        this.mode = mode;
        this.eventContext = eventContext;
        this.participants = new LinkedHashSet<>(participants);
        this.selectedGames = new ArrayList<>(selectedGames);
        this.globalScoreAtSeriesStart = new HashMap<>(globalScoreAtSeriesStart);
    }

    public UUID instanceId() {
        return instanceId;
    }

    public SessionMode mode() {
        return mode;
    }

    public Optional<EventExecutionContext> eventContext() {
        return Optional.ofNullable(eventContext);
    }

    public Set<UUID> participants() {
        return Set.copyOf(participants);
    }

    public void removeParticipant(UUID playerId) {
        participants.remove(playerId);
        if (currentRound != null) currentRound.removePlayer(playerId);
    }

    public void addParticipant(UUID playerId, int globalScoreAtStart) {
        participants.add(playerId);
        forfeitedParticipants.remove(playerId);
        globalScoreAtSeriesStart.putIfAbsent(playerId, globalScoreAtStart);
    }

    public void forfeitParticipant(UUID playerId) {
        if (participants.remove(playerId)) {
            forfeitedParticipants.add(playerId);
        }
        if (currentRound != null) currentRound.removePlayer(playerId);
    }

    public boolean contains(UUID playerId) {
        return participants.contains(playerId);
    }

    public boolean forfeited(UUID playerId) {
        return forfeitedParticipants.contains(playerId);
    }

    public Set<UUID> forfeitedParticipants() {
        return Set.copyOf(forfeitedParticipants);
    }

    public int activeParticipantCount() {
        return participants.size();
    }

    public List<MinigameDefinition> selectedGames() {
        return List.copyOf(selectedGames);
    }

    public void replaceSelectedGames(List<MinigameDefinition> selectedGames) {
        this.selectedGames.clear();
        this.selectedGames.addAll(selectedGames);
        this.nextRoundIndex = 0;
        this.forcedNextGame = null;
    }

    public boolean hasNextRound() {
        return nextRoundIndex < selectedGames.size() || forcedNextGame != null;
    }

    public MinigameDefinition consumeNextGame() {
        if (forcedNextGame != null) {
            MinigameDefinition forced = forcedNextGame;
            forcedNextGame = null;
            return forced;
        }
        return selectedGames.get(nextRoundIndex++);
    }

    public int nextRoundNumber() {
        return nextRoundIndex + 1;
    }

    public SeriesScore seriesScore() {
        return seriesScore;
    }

    public int globalScoreAtSeriesStart(UUID playerId) {
        return globalScoreAtSeriesStart.getOrDefault(playerId, 0);
    }

    public Map<UUID, Integer> globalScoreAtSeriesStart() {
        return Map.copyOf(globalScoreAtSeriesStart);
    }

    public SeriesState state() {
        return state;
    }

    public void state(SeriesState state) {
        this.state = state;
    }

    public int stateTicksRemaining() {
        return stateTicksRemaining;
    }

    public void stateTicksRemaining(int ticks) {
        this.stateTicksRemaining = Math.max(0, ticks);
    }

    public void decrementStateTicks() {
        if (stateTicksRemaining > 0) stateTicksRemaining--;
    }

    public RoundSession currentRound() {
        return currentRound;
    }

    public void currentRound(RoundSession currentRound) {
        this.currentRound = currentRound;
    }

    public void forcedNextGame(MinigameDefinition forcedNextGame) {
        this.forcedNextGame = forcedNextGame;
    }

    public Optional<MinigameDefinition> forcedNextGame() {
        return Optional.ofNullable(forcedNextGame);
    }

    public boolean markTerminalNotified() {
        if (terminalNotified) return false;
        terminalNotified = true;
        return true;
    }

    public void markSeriesStarted() {
        seriesStarted = true;
    }

    public boolean seriesStarted() {
        return seriesStarted;
    }

    public boolean markGlobalCommitted(UUID playerId) {
        return committedParticipants.add(playerId);
    }

    public boolean canBeginPregame(int requiredPlayers) {
        return activeParticipantCount() >= requiredPlayers;
    }

    public boolean canAcceptParticipant(int maxPlayers) {
        return activeParticipantCount() < maxPlayers;
    }

    public boolean shouldAbortPregame(int requiredPlayers) {
        return (state == SeriesState.PRE_GAME_DRAW || state == SeriesState.PRE_GAME_COUNTDOWN)
                && activeParticipantCount() < requiredPlayers;
    }

    public boolean shouldFinishForMinimumContinuation(int requiredPlayers) {
        if (!seriesStarted) return false;
        if (state == SeriesState.FINISHED || state == SeriesState.CANCELLED || state == SeriesState.SERIES_RESULTS) return false;
        return activeParticipantCount() < requiredPlayers;
    }
}
