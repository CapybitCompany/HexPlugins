package hexcasino.machine;

import hexcasino.config.CasinoConfig;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class BusDriverSession {
    public enum State { IDLE, PLAYING, PAYOUT_PENDING, SHOWING_RESULT }

    private final UUID playerId;
    private final CasinoConfig.Machine machine;
    private final Inventory inventory;
    private State state = State.IDLE;
    private int betIndex;
    private int boardIndex;
    private BusDriverBoard board;
    private long gameId;
    private int stageIndex;
    private int completedRounds;
    private double stake;
    private double currentWin;
    private boolean ending;
    private boolean suppressCloseReopen;
    private boolean actionLocked;
    private Location lockedLocation;
    private boolean wagerPaid;
    private boolean settled = true;

    private long stageStartNano;
    private long stageDeadlineNano;
    private int decisionTimeMs;
    private long stageFrameSeq;
    private int windowId = -1;
    private boolean stageExpired;
    private BukkitTask timerTask;
    private BukkitTask timeoutTask;
    private final Map<Integer, StageFrameSnapshot> snapshotsByStateId = new LinkedHashMap<>();
    private long latestLogicalElapsedMs;
    private long latestLogicalFrameStartNano;
    private boolean latestLogicalOpen;

    public BusDriverSession(UUID playerId, CasinoConfig.Machine machine, Inventory inventory, int betIndex) {
        this.playerId = playerId;
        this.machine = machine;
        this.inventory = inventory;
        this.betIndex = betIndex;
    }

    public UUID playerId(){ return playerId; }
    public CasinoConfig.Machine machine(){ return machine; }
    public Inventory inventory(){ return inventory; }
    public State state(){ return state; }
    public void state(State v){ state=v; }
    public int betIndex(){ return betIndex; }
    public void betIndex(int v){ betIndex=v; }
    public int boardIndex(){ return boardIndex; }
    public void boardIndex(int v){ boardIndex=v; }
    public BusDriverBoard board(){ return board; }
    public void board(BusDriverBoard v){ board=v; }
    public long gameId(){ return gameId; }
    public void gameId(long v){ gameId=v; }
    public int stageIndex(){ return stageIndex; }
    public void stageIndex(int v){ stageIndex=v; }
    public int completedRounds(){ return completedRounds; }
    public void completedRounds(int v){ completedRounds=v; }
    public double stake(){ return stake; }
    public void stake(double v){ stake=v; }
    public double currentWin(){ return currentWin; }
    public void currentWin(double v){ currentWin=v; }
    public boolean ending(){ return ending; }
    public void ending(boolean v){ ending=v; }
    public boolean suppressCloseReopen(){ return suppressCloseReopen; }
    public void suppressCloseReopen(boolean v){ suppressCloseReopen=v; }
    public boolean actionLocked(){ return actionLocked; }
    public void actionLocked(boolean v){ actionLocked=v; }
    public Location lockedLocation(){ return lockedLocation; }
    public void lockedLocation(Location v){ lockedLocation=v; }
    public boolean wagerPaid(){ return wagerPaid; }
    public void wagerPaid(boolean v){ wagerPaid=v; }
    public boolean settled(){ return settled; }
    public void settled(boolean v){ settled=v; }

    public BusDriverBoard.StageDefinition currentStage(){ return board == null ? null : board.stage(stageIndex); }
    public long stageStartNano(){ return stageStartNano; }
    public void stageStartNano(long v){ stageStartNano=v; }
    public long stageDeadlineNano(){ return stageDeadlineNano; }
    public void stageDeadlineNano(long v){ stageDeadlineNano=v; }
    public int decisionTimeMs(){ return decisionTimeMs; }
    public void decisionTimeMs(int v){ decisionTimeMs=v; }
    public long stageFrameSeq(){ return stageFrameSeq; }
    public long nextStageFrameSeq(){ return ++stageFrameSeq; }
    public int windowId(){ return windowId; }
    public void observeWindowId(int value){ if (value > 0 && windowId <= 0) windowId = value; }
    public boolean stageExpired(){ return stageExpired; }
    public void stageExpired(boolean v){ stageExpired=v; }
    public BukkitTask timerTask(){ return timerTask; }
    public void timerTask(BukkitTask v){ timerTask=v; }
    public BukkitTask timeoutTask(){ return timeoutTask; }
    public void timeoutTask(BukkitTask v){ timeoutTask=v; }

    public void setLatestLogicalFrame(long elapsedMs, long frameStartNano, boolean open) {
        latestLogicalElapsedMs = elapsedMs;
        latestLogicalFrameStartNano = frameStartNano;
        latestLogicalOpen = open;
        nextStageFrameSeq();
    }

    public void captureStateId(int stateId, int historyMs) {
        if (stateId < 0 || gameId <= 0 || board == null) return;
        long now = System.nanoTime();
        StageFrameSnapshot snap = new StageFrameSnapshot(gameId, stageIndex, stageFrameSeq, windowId, stateId,
                latestLogicalElapsedMs, latestLogicalFrameStartNano, latestLogicalOpen, currentWin > 0.0D);
        snapshotsByStateId.put(stateId, snap);
        long cutoff = now - (historyMs * 1_000_000L);
        snapshotsByStateId.entrySet().removeIf(e -> e.getValue().frameStartNano() < cutoff);
    }

    public StageFrameSnapshot snapshot(int stateId){ return snapshotsByStateId.get(stateId); }
    public void clearStageSnapshots(){ snapshotsByStateId.clear(); windowId=-1; stageFrameSeq=0; latestLogicalElapsedMs=0L; latestLogicalFrameStartNano=System.nanoTime(); latestLogicalOpen=false; }
    public void cancelTimers(){
        if (timerTask != null) timerTask.cancel();
        if (timeoutTask != null) timeoutTask.cancel();
        timerTask=null; timeoutTask=null;
    }
}
