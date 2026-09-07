package hex.parkour.model;

import java.util.HashSet;
import java.util.Set;

public final class ParkourAttempt {
    private final String arenaId;
    private long startedAtNanos;
    private long finishedDurationNanos = -1L;
    private int checkpointOrder;
    private String checkpointId;
    private final Set<String> claimedMoneyPoints = new HashSet<>();

    public ParkourAttempt(String arenaId) {
        this.arenaId = arenaId;
    }

    public String arenaId() {
        return arenaId;
    }

    public boolean timerStarted() {
        return startedAtNanos > 0L;
    }

    public void start(long nowNanos) {
        if (!timerStarted()) startedAtNanos = nowNanos;
    }

    public boolean finished() {
        return finishedDurationNanos >= 0L;
    }

    public void finish(long nowNanos) {
        if (timerStarted() && !finished()) finishedDurationNanos = nowNanos - startedAtNanos;
    }

    public long elapsedNanos(long nowNanos) {
        if (!timerStarted()) return 0L;
        if (finished()) return finishedDurationNanos;
        return Math.max(0L, nowNanos - startedAtNanos);
    }

    public int checkpointOrder() {
        return checkpointOrder;
    }

    public String checkpointId() {
        return checkpointId;
    }

    public void checkpoint(ParkourCheckpoint checkpoint) {
        checkpointOrder = checkpoint.order();
        checkpointId = checkpoint.id();
    }

    public boolean claimMoneyPoint(String id) {
        return claimedMoneyPoints.add(id);
    }

    public boolean moneyPointClaimed(String id) {
        return claimedMoneyPoints.contains(id);
    }
}
