package hex.minigames.game.supermemory;

import hex.minigames.game.PlayerRoundResult;
import hex.minigames.game.RoundResult;
import hex.minigames.model.BlockPosition;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class SuperMemoryRuntime {
    private final SuperMemoryConfig config;
    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();
    private long roundStartNanos = -1L;
    private RoundResult cachedResult;

    public SuperMemoryRuntime(SuperMemoryConfig config, Collection<UUID> participants, Random random, Board board) {
        this.config = config;
        List<SuperMemoryConfig.StationConfig> stations = new ArrayList<>(config.stations());
        shuffle(stations, random);
        int stationIndex = 0;
        for (UUID playerId : participants) {
            if (stationIndex >= stations.size()) break;
            SuperMemoryConfig.StationConfig station = stations.get(stationIndex++);
            List<BlockPosition> sequence = new ArrayList<>(station.clickBlocks());
            shuffle(sequence, random);
            players.put(playerId, new PlayerState(station, sequence));
            setStation(board, station, config.idleMaterial());
        }
    }

    public void start(long nowNanos) {
        roundStartNanos = nowNanos;
    }

    public ClickResult click(UUID playerId, BlockPosition clicked, long tick, long nowNanos, Board board) {
        PlayerState state = players.get(playerId);
        if (state == null || state.status != PlayerStatus.ACTIVE) return ClickResult.ignored();
        if (roundStartNanos < 0L) return ClickResult.ignored();
        if (state.resetPending) return ClickResult.ignored();
        if (state.lastClickTick == tick) return ClickResult.ignored();
        state.lastClickTick = tick;
        if (!state.station.clickBlocks().contains(clicked)) return ClickResult.ignored();

        BlockPosition expected = state.sequence.get(state.progress);
        if (!expected.equals(clicked)) {
            state.progress = 0;
            state.resetPending = true;
            board.setBlock(clicked, config.wrongMaterial());
            cachedResult = null;
            return new ClickResult(ClickOutcome.WRONG, 0, clicked);
        }

        board.setBlock(clicked, config.correctMaterial());
        state.progress++;
        cachedResult = null;
        if (state.progress >= state.sequence.size()) {
            state.status = PlayerStatus.FINISHED;
            state.completionNanos = Math.max(0L, nowNanos - roundStartNanos);
            return new ClickResult(ClickOutcome.FINISHED, state.progress, clicked);
        }
        return new ClickResult(ClickOutcome.CORRECT, state.progress, clicked);
    }

    public void resetWrong(UUID playerId, Board board) {
        PlayerState state = players.get(playerId);
        if (state == null) return;
        setStation(board, state.station, config.idleMaterial());
        state.resetPending = false;
    }

    public void remove(UUID playerId) {
        players.remove(playerId);
        cachedResult = null;
    }

    public void markTimeouts() {
        for (PlayerState state : players.values()) {
            if (state.status == PlayerStatus.ACTIVE) state.status = PlayerStatus.DNF;
        }
        cachedResult = null;
    }

    public boolean allResolved() {
        if (players.isEmpty()) return false;
        for (PlayerState state : players.values()) {
            if (state.status == PlayerStatus.ACTIVE) return false;
        }
        return true;
    }

    public RoundResult result() {
        if (cachedResult != null) return cachedResult;
        Map<UUID, PlayerRoundResult> out = new LinkedHashMap<>();
        List<RankedPlayer> ranked = ranking();
        int placement = 1;
        Set<UUID> completed = ranked.stream().map(RankedPlayer::playerId).collect(java.util.stream.Collectors.toSet());
        for (RankedPlayer player : ranked) {
            int points = config.pointsForPlacement(placement);
            out.put(player.playerId(), new PlayerRoundResult(
                    points,
                    OptionalInt.of(placement),
                    true,
                    false,
                    Map.of(
                            "completion_time_ms", String.valueOf(player.completionMillis()),
                            "completion_time", formatMillis(player.completionMillis())
                    )
            ));
            placement++;
        }
        for (UUID playerId : players.keySet()) {
            if (completed.contains(playerId)) continue;
            out.put(playerId, new PlayerRoundResult(0, OptionalInt.empty(), false, true, Map.of("dnf", "true")));
        }
        cachedResult = new RoundResult(out, Map.of("game", SuperMemoryConfig.ID));
        return cachedResult;
    }

    public List<RankedPlayer> ranking() {
        return players.entrySet().stream()
                .filter(entry -> entry.getValue().status == PlayerStatus.FINISHED)
                .map(entry -> new RankedPlayer(entry.getKey(), entry.getValue().completionNanos))
                .sorted(Comparator.comparingLong(RankedPlayer::completionNanos))
                .toList();
    }

    public void cleanup(Board board) {
        for (SuperMemoryConfig.StationConfig station : config.stations()) {
            setStation(board, station, config.idleMaterial());
        }
    }

    public List<BlockPosition> sequence(UUID playerId) {
        PlayerState state = players.get(playerId);
        return state == null ? List.of() : List.copyOf(state.sequence);
    }

    public SuperMemoryConfig.StationConfig station(UUID playerId) {
        PlayerState state = players.get(playerId);
        return state == null ? null : state.station;
    }

    public boolean stationContains(UUID playerId, BlockPosition position) {
        PlayerState state = players.get(playerId);
        return state != null && state.station.region().contains(position);
    }

    public int progress(UUID playerId) {
        PlayerState state = players.get(playerId);
        return state == null ? 0 : state.progress;
    }

    public boolean finished(UUID playerId) {
        PlayerState state = players.get(playerId);
        return state != null && state.status == PlayerStatus.FINISHED;
    }

    public boolean dnf(UUID playerId) {
        PlayerState state = players.get(playerId);
        return state != null && state.status == PlayerStatus.DNF;
    }

    public long remainingMillis(long nowNanos) {
        if (roundStartNanos < 0L) return config.roundDurationSeconds() * 1000L;
        long elapsedMillis = Math.max(0L, (nowNanos - roundStartNanos) / 1_000_000L);
        return Math.max(0L, config.roundDurationSeconds() * 1000L - elapsedMillis);
    }

    public long completionMillis(UUID playerId) {
        long nanos = completionNanos(playerId);
        return nanos < 0L ? -1L : nanos / 1_000_000L;
    }

    public long completionNanos(UUID playerId) {
        PlayerState state = players.get(playerId);
        return state == null ? -1L : state.completionNanos;
    }

    public static String formatMillis(long millis) {
        long seconds = millis / 1000L;
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        long milliseconds = millis % 1000L;
        return String.format("%02d:%02d.%03d", minutes, remainingSeconds, milliseconds);
    }

    public static String formatClock(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        return String.format("%02d:%02d", minutes, remainingSeconds);
    }

    private void setStation(Board board, SuperMemoryConfig.StationConfig station, Material material) {
        for (BlockPosition block : station.clickBlocks()) {
            board.setBlock(block, material);
        }
    }

    private static <T> void shuffle(List<T> values, Random random) {
        if (random == null) java.util.Collections.shuffle(values);
        else java.util.Collections.shuffle(values, random);
    }

    public interface Board {
        void setBlock(BlockPosition position, Material material);
    }

    public enum ClickOutcome {
        IGNORED,
        CORRECT,
        WRONG,
        FINISHED
    }

    public record ClickResult(ClickOutcome outcome, int progress, BlockPosition clicked) {
        public static ClickResult ignored() {
            return new ClickResult(ClickOutcome.IGNORED, 0, null);
        }
    }

    public record RankedPlayer(UUID playerId, long completionNanos) {
        public long completionMillis() {
            return completionNanos / 1_000_000L;
        }
    }

    private enum PlayerStatus {
        ACTIVE,
        FINISHED,
        DNF
    }

    private static final class PlayerState {
        private final SuperMemoryConfig.StationConfig station;
        private final List<BlockPosition> sequence;
        private int progress;
        private PlayerStatus status = PlayerStatus.ACTIVE;
        private long completionNanos = -1L;
        private long lastClickTick = Long.MIN_VALUE;
        private boolean resetPending;

        private PlayerState(SuperMemoryConfig.StationConfig station, List<BlockPosition> sequence) {
            this.station = station;
            this.sequence = List.copyOf(sequence);
        }
    }
}
