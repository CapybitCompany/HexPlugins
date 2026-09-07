package hex.parkour.model;

import java.util.UUID;

public final class ParkourPlayerSession {
    private final UUID playerId;
    private final UUID instanceId;
    private final boolean standalone;
    private ParkourPlayerState state = ParkourPlayerState.PARKOUR_LOBBY;
    private String currentArenaId;
    private ParkourAttempt attempt;
    private boolean playersHidden;
    private long portalCooldownUntilMillis;
    private long resetCooldownUntilMillis;
    private int lastBlockX = Integer.MIN_VALUE;
    private int lastBlockY = Integer.MIN_VALUE;
    private int lastBlockZ = Integer.MIN_VALUE;

    public ParkourPlayerSession(UUID playerId, UUID instanceId) {
        this(playerId, instanceId, false);
    }

    public ParkourPlayerSession(UUID playerId, UUID instanceId, boolean standalone) {
        this.playerId = playerId;
        this.instanceId = instanceId;
        this.standalone = standalone;
    }

    public UUID playerId() { return playerId; }
    public UUID instanceId() { return instanceId; }
    public boolean standalone() { return standalone; }
    public ParkourPlayerState state() { return state; }
    public String currentArenaId() { return currentArenaId; }
    public ParkourAttempt attempt() { return attempt; }
    public boolean playersHidden() { return playersHidden; }

    public void lobby() {
        state = ParkourPlayerState.PARKOUR_LOBBY;
        currentArenaId = null;
        attempt = null;
    }

    public void arena(String arenaId) {
        state = ParkourPlayerState.ARENA;
        currentArenaId = arenaId;
        attempt = new ParkourAttempt(arenaId);
    }

    public void finished() {
        state = ParkourPlayerState.FINISHED;
    }

    public void playersHidden(boolean playersHidden) {
        this.playersHidden = playersHidden;
    }

    public boolean portalCooldownActive(long nowMillis) {
        return nowMillis < portalCooldownUntilMillis;
    }

    public void startPortalCooldown(long nowMillis, long ticks) {
        portalCooldownUntilMillis = nowMillis + ticks * 50L;
    }

    public boolean resetCooldownActive(long nowMillis) {
        return nowMillis < resetCooldownUntilMillis;
    }

    public void startResetCooldown(long nowMillis, long ticks) {
        resetCooldownUntilMillis = nowMillis + ticks * 50L;
    }

    public boolean changedBlock(int x, int y, int z) {
        if (x == lastBlockX && y == lastBlockY && z == lastBlockZ) return false;
        lastBlockX = x;
        lastBlockY = y;
        lastBlockZ = z;
        return true;
    }
}
