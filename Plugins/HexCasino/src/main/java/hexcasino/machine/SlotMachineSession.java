package hexcasino.machine;

import hexcasino.config.CasinoConfig;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Mutable runtime state for one player. Packet-facing methods are synchronized. */
public final class SlotMachineSession {
    public enum State { IDLE, ROLLING, SHOWING_RESULT }
    public enum ReelState { SPINNING, STOPPED }

    public enum StopRejectReason {
        NONE, NOT_ROLLING, WINDOW_MISMATCH, ALREADY_COMPLETE, UNMAPPED_STATE,
        FOREIGN_GAME, REPLAYED_STATE, STALE_STATE, FUTURE_STATE, NON_MONOTONIC_FRAME, NO_STOP_UNIT
    }

    public record PacketStopResolution(ResolvedStop stop, StopRejectReason rejection) {
        public PacketStopResolution {
            Objects.requireNonNull(rejection, "rejection");
            if ((stop == null) == (rejection == StopRejectReason.NONE)) {
                throw new IllegalArgumentException("Exactly one of resolved stop or rejection must be present");
            }
        }
        public static PacketStopResolution resolved(ResolvedStop stop) {
            return new PacketStopResolution(Objects.requireNonNull(stop), StopRejectReason.NONE);
        }
        public static PacketStopResolution rejected(StopRejectReason reason) {
            if (reason == StopRejectReason.NONE) throw new IllegalArgumentException("NONE is not a rejection");
            return new PacketStopResolution(null, reason);
        }
        public boolean resolved() { return stop != null; }
    }

    private final UUID playerId;
    private final String playerName;
    private final CasinoConfig.Machine machine;
    private final Inventory inventory;

    private SlotLayout layout;
    private String[] displaySymbols;
    private ReelState[] reelStates;
    private volatile State state = State.IDLE;
    private int costIndex;
    private int difficultyIndex;
    private int practiceSetIndex = 1;
    private BukkitTask rollTask;
    private boolean ending;
    private boolean suppressCloseReopen;
    private Location lockedLocation;

    private long gameId;
    private DeterministicReelSet activeSet;
    private boolean practice;
    private double chargedStake;
    private long startNano;
    private long frameDurationNanos;
    private long lastFrameSeq = -1L;
    private int[] startPositions;
    private int[] stoppedPositions;
    private int stoppedCount;
    private FrameSnapshot currentSnapshot;
    private final Deque<FrameSnapshot> snapshotHistory = new ArrayDeque<>();
    private final Map<Integer, FrameSnapshot> snapshotsByStateId = new HashMap<>();
    private final Set<Integer> consumedStateIds = new HashSet<>();
    private final List<ResolvedStop> resolvedStops = new ArrayList<>();
    private long lastResolvedFrameSeq = -1L;
    private int rejectedStopCount;
    private volatile boolean strictPacketInput;
    private int currentWindowId = -1;
    private boolean payoutSettled;

    public SlotMachineSession(UUID playerId, String playerName, CasinoConfig.Machine machine,
                              Inventory inventory, int costIndex, int difficultyIndex,
                              SlotLayout layout, String[] initialSymbols) {
        this.playerId = Objects.requireNonNull(playerId);
        this.playerName = Objects.requireNonNull(playerName);
        this.machine = Objects.requireNonNull(machine);
        this.inventory = Objects.requireNonNull(inventory);
        this.costIndex = costIndex;
        this.difficultyIndex = difficultyIndex;
        changeLayout(layout, initialSymbols);
    }

    public UUID playerId() { return playerId; }
    public String playerName() { return playerName; }
    public CasinoConfig.Machine machine() { return machine; }
    public Inventory inventory() { return inventory; }
    public State state() { return state; }
    public void state(State state) { this.state = state; }
    public int costIndex() { return costIndex; }
    public void costIndex(int costIndex) { this.costIndex = costIndex; }
    public int difficultyIndex() { return difficultyIndex; }
    public void difficultyIndex(int difficultyIndex) { this.difficultyIndex = difficultyIndex; }
    public int practiceSetIndex() { return practiceSetIndex; }
    public void practiceSetIndex(int value) { this.practiceSetIndex = value; }
    public SlotLayout layout() { return layout; }
    public boolean ending() { return ending; }
    public void ending(boolean ending) { this.ending = ending; }
    public boolean suppressCloseReopen() { return suppressCloseReopen; }
    public void suppressCloseReopen(boolean value) { this.suppressCloseReopen = value; }
    public Location lockedLocation() { return lockedLocation; }
    public void lockedLocation(Location value) { this.lockedLocation = value; }
    public BukkitTask rollTask() { return rollTask; }
    public void rollTask(BukkitTask value) { this.rollTask = value; }
    public boolean practice() { return practice; }
    public double chargedStake() { return chargedStake; }
    public long gameId() { return gameId; }
    public DeterministicReelSet activeSet() { return activeSet; }
    public long frameDurationNanos() { return frameDurationNanos; }
    public boolean strictPacketInput() { return strictPacketInput; }
    public boolean payoutSettled() { return payoutSettled; }
    public void payoutSettled(boolean value) { payoutSettled = value; }
    public List<ResolvedStop> resolvedStops() { synchronized (this) { return List.copyOf(resolvedStops); } }

    public void changeLayout(SlotLayout layout, String[] initialSymbols) {
        if (state != State.IDLE) throw new IllegalStateException("Layout can only change while IDLE");
        this.layout = Objects.requireNonNull(layout);
        if (initialSymbols.length != layout.cellCount()) throw new IllegalArgumentException("initial symbol count mismatch");
        this.displaySymbols = Arrays.copyOf(initialSymbols, initialSymbols.length);
        this.reelStates = new ReelState[layout.stopUnitCount()];
        Arrays.fill(reelStates, ReelState.STOPPED);
    }

    public synchronized void startGame(long gameId, DeterministicReelSet set, boolean practice,
                                       double chargedStake, long startNano, long frameDurationNanos,
                                       boolean strictPacketInput) {
        this.gameId = gameId;
        this.activeSet = Objects.requireNonNull(set);
        this.practice = practice;
        this.chargedStake = chargedStake;
        this.startNano = startNano;
        this.frameDurationNanos = frameDurationNanos;
        this.strictPacketInput = strictPacketInput;
        this.lastFrameSeq = -1L;
        this.startPositions = Arrays.copyOf(set.startPositions(), layout.stopUnitCount());
        this.stoppedPositions = new int[layout.stopUnitCount()];
        Arrays.fill(stoppedPositions, -1);
        this.reelStates = new ReelState[layout.stopUnitCount()];
        Arrays.fill(reelStates, ReelState.SPINNING);
        this.stoppedCount = 0;
        this.currentSnapshot = null;
        this.snapshotHistory.clear();
        this.snapshotsByStateId.clear();
        this.consumedStateIds.clear();
        this.resolvedStops.clear();
        this.lastResolvedFrameSeq = -1L;
        this.rejectedStopCount = 0;
        this.currentWindowId = -1;
        this.payoutSettled = false;
        this.state = State.ROLLING;
    }

    public synchronized void resumeGame(long gameId, DeterministicReelSet set, double chargedStake,
                                        long startNano, long frameDurationNanos, boolean strictPacketInput,
                                        int[] resumePositions, boolean[] stoppedMask, List<ResolvedStop> previousStops) {
        if (resumePositions.length != layout.stopUnitCount() || stoppedMask.length != layout.stopUnitCount()) {
            throw new IllegalArgumentException("resume arrays must match stop unit count");
        }
        this.gameId = gameId;
        this.activeSet = Objects.requireNonNull(set);
        this.practice = false;
        this.chargedStake = chargedStake;
        this.startNano = startNano;
        this.frameDurationNanos = frameDurationNanos;
        this.strictPacketInput = strictPacketInput;
        this.lastFrameSeq = -1L;
        this.startPositions = Arrays.copyOf(resumePositions, resumePositions.length);
        this.stoppedPositions = new int[layout.stopUnitCount()];
        this.reelStates = new ReelState[layout.stopUnitCount()];
        this.stoppedCount = 0;
        for (int i = 0; i < layout.stopUnitCount(); i++) {
            if (stoppedMask[i]) {
                reelStates[i] = ReelState.STOPPED;
                stoppedPositions[i] = resumePositions[i];
                stoppedCount++;
            } else {
                reelStates[i] = ReelState.SPINNING;
                stoppedPositions[i] = -1;
            }
        }
        this.currentSnapshot = null;
        this.snapshotHistory.clear();
        this.snapshotsByStateId.clear();
        this.consumedStateIds.clear();
        this.resolvedStops.clear();
        this.resolvedStops.addAll(Objects.requireNonNull(previousStops, "previousStops"));
        this.lastResolvedFrameSeq = -1L; // new monotonic timing segment after reconnect/restart
        this.rejectedStopCount = 0;
        this.currentWindowId = -1;
        this.payoutSettled = false;
        this.state = State.ROLLING;
    }

    public synchronized boolean[] stoppedMaskCopy() {
        boolean[] out = new boolean[reelStates.length];
        for (int i = 0; i < out.length; i++) out[i] = reelStates[i] == ReelState.STOPPED;
        return out;
    }

    public synchronized int[] currentPositionsCopy() {
        if (currentSnapshot != null) return currentSnapshot.reelPositions();
        int[] out = new int[reelStates.length];
        for (int i = 0; i < out.length; i++) out[i] = reelStates[i] == ReelState.STOPPED ? stoppedPositions[i] : startPositions[i];
        return out;
    }

    public synchronized FrameSnapshot computeSnapshot(long nowNano, SlotEngine engine) {
        if (state != State.ROLLING || activeSet == null) return currentSnapshot;
        long elapsed = Math.max(0L, nowNano - startNano);
        long frame = Math.min(DeterministicReelSetRepository.STRIP_LENGTH - 1L,
                elapsed / Math.max(1L, frameDurationNanos));
        int[] positions = new int[layout.stopUnitCount()];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = reelStates[i] == ReelState.STOPPED
                    ? stoppedPositions[i]
                    : Math.floorMod(startPositions[i] + (int) frame, DeterministicReelSetRepository.STRIP_LENGTH);
        }
        String[] visible = engine.visibleSymbols(activeSet, layout, positions);
        long frameStart = startNano + (frame * frameDurationNanos);
        FrameSnapshot snapshot = new FrameSnapshot(gameId, frame, -1, frameStart, frameStart + frameDurationNanos, positions, visible);
        currentSnapshot = snapshot;
        if (frame != lastFrameSeq) {
            lastFrameSeq = frame;
            snapshotHistory.addLast(snapshot);
        }
        return snapshot;
    }

    public synchronized boolean reachedCycleEnd(long nowNano) {
        if (state != State.ROLLING) return false;
        return (nowNano - startNano) / Math.max(1L, frameDurationNanos) >= DeterministicReelSetRepository.STRIP_LENGTH - 1L;
    }

    public synchronized void pruneSnapshots(long nowNano, int historyMs) {
        long cutoff = nowNano - (historyMs * 1_000_000L);
        while (!snapshotHistory.isEmpty() && snapshotHistory.peekFirst().frameEndNano() < cutoff) {
            FrameSnapshot removed = snapshotHistory.removeFirst();
            snapshotsByStateId.entrySet().removeIf(entry -> entry.getValue().frameSeq() == removed.frameSeq());
        }
    }

    /** Called from PacketEvents send listener. One or many state IDs may map to the same immutable visual frame. */
    public synchronized void bindContainerState(int windowId, int stateId) {
        if (state != State.ROLLING || currentSnapshot == null) return;
        // Window 0 is the player's own inventory and negative ids are cursor/special updates.
        // They used to overwrite currentWindowId and caused valid Reel Challenge clicks to fail
        // with WINDOW_MISMATCH. Lock the session to the first positive container id instead.
        if (windowId <= 0) return;
        if (currentWindowId < 0) currentWindowId = windowId;
        if (windowId != currentWindowId) return;
        snapshotsByStateId.put(stateId, currentSnapshot.withContainerStateId(stateId));
    }

    public synchronized PacketStopResolution resolvePacketStop(int windowId, int stateId,
                                                                  long packetReceiveNano, int maxDelayMs) {
        if (state != State.ROLLING) return reject(StopRejectReason.NOT_ROLLING);
        if (windowId != currentWindowId) return reject(StopRejectReason.WINDOW_MISMATCH);
        if (allStopped()) return reject(StopRejectReason.ALREADY_COMPLETE);
        if (consumedStateIds.contains(stateId)) return reject(StopRejectReason.REPLAYED_STATE);

        FrameSnapshot snapshot = snapshotsByStateId.get(stateId);
        if (snapshot == null) return reject(StopRejectReason.UNMAPPED_STATE);
        if (snapshot.gameId() != gameId) return reject(StopRejectReason.FOREIGN_GAME);
        if (packetReceiveNano - snapshot.frameEndNano() > maxDelayMs * 1_000_000L) {
            return reject(StopRejectReason.STALE_STATE);
        }
        if (snapshot.frameStartNano() > packetReceiveNano) return reject(StopRejectReason.FUTURE_STATE);
        // A second STOP may not reuse the same (or an older) visual frame via another stateId.
        if (snapshot.frameSeq() <= lastResolvedFrameSeq) return reject(StopRejectReason.NON_MONOTONIC_FRAME);

        int stopUnit = nextSpinningUnit();
        if (stopUnit < 0) return reject(StopRejectReason.NO_STOP_UNIT);
        int position = snapshot.reelPosition(stopUnit);
        stopAt(stopUnit, position);
        consumedStateIds.add(stateId);
        lastResolvedFrameSeq = snapshot.frameSeq();
        rejectedStopCount = 0;
        ResolvedStop resolved = new ResolvedStop(stopUnit, snapshot.frameSeq(), stateId,
                snapshot.frameStartNano(), packetReceiveNano, position);
        resolvedStops.add(resolved);
        return PacketStopResolution.resolved(resolved);
    }

    private PacketStopResolution reject(StopRejectReason reason) {
        rejectedStopCount++;
        return PacketStopResolution.rejected(reason);
    }

    public synchronized int rejectedStopCount() { return rejectedStopCount; }

    public synchronized Optional<ResolvedStop> resolvePracticeCurrentFrameStop(long receiveNano) {
        if (state != State.ROLLING || currentSnapshot == null || allStopped()) return Optional.empty();
        int stopUnit = nextSpinningUnit();
        if (stopUnit < 0) return Optional.empty();
        int position = currentSnapshot.reelPosition(stopUnit);
        stopAt(stopUnit, position);
        ResolvedStop resolved = new ResolvedStop(stopUnit, currentSnapshot.frameSeq(), -1,
                currentSnapshot.frameStartNano(), receiveNano, position);
        resolvedStops.add(resolved);
        return Optional.of(resolved);
    }

    public synchronized void stopAllAtCurrentFrame(long receiveNano) {
        if (currentSnapshot == null) return;
        while (!allStopped()) {
            int unit = nextSpinningUnit();
            int position = currentSnapshot.reelPosition(unit);
            stopAt(unit, position);
            resolvedStops.add(new ResolvedStop(unit, currentSnapshot.frameSeq(), -2,
                    currentSnapshot.frameStartNano(), receiveNano, position));
        }
    }

    private void stopAt(int stopUnit, int position) {
        if (reelStates[stopUnit] != ReelState.SPINNING) return;
        stoppedPositions[stopUnit] = position;
        reelStates[stopUnit] = ReelState.STOPPED;
        stoppedCount++;
    }

    private int nextSpinningUnit() {
        for (int i = 0; i < reelStates.length; i++) if (reelStates[i] == ReelState.SPINNING) return i;
        return -1;
    }

    public synchronized boolean allStopped() { return stoppedCount >= reelStates.length; }
    public synchronized int stoppedCount() { return stoppedCount; }
    public synchronized int stopUnitCount() { return reelStates.length; }
    public synchronized ReelState reelState(int unit) { return reelStates[unit]; }
    public synchronized int[] stoppedPositionsCopy() { return Arrays.copyOf(stoppedPositions, stoppedPositions.length); }
    public synchronized FrameSnapshot currentSnapshot() { return currentSnapshot; }

    public synchronized String displaySymbol(int index) { return displaySymbols[index]; }
    public synchronized void displaySymbols(String[] symbols) {
        if (symbols.length != displaySymbols.length) throw new IllegalArgumentException("display symbol count mismatch");
        displaySymbols = Arrays.copyOf(symbols, symbols.length);
    }
    public synchronized String[] displaySymbolsCopy() { return Arrays.copyOf(displaySymbols, displaySymbols.length); }
}
