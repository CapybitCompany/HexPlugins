package hexcasino.machine;

import hexcasino.config.CasinoConfig;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.Arrays;
import java.util.UUID;

public final class SlotMachineSession {

    public enum State {
        IDLE,
        ROLLING,
        SHOWING_RESULT
    }

    private final UUID playerId;
    private final CasinoConfig.Machine machine;
    private final Inventory inventory;
    private final String[] symbols = new String[9];
    private final boolean[] stoppedColumns = new boolean[3];

    private State state = State.IDLE;
    private int betIndex;
    private int lineIndex;
    private int stoppedColumnCount;
    private BukkitTask rollTask;
    private boolean ending;
    private boolean suppressCloseReopen;
    private Location lockedLocation;

    public SlotMachineSession(UUID playerId,
                              CasinoConfig.Machine machine,
                              Inventory inventory,
                              int betIndex,
                              int lineIndex,
                              String[] initialSymbols) {
        this.playerId = playerId;
        this.machine = machine;
        this.inventory = inventory;
        this.betIndex = betIndex;
        this.lineIndex = lineIndex;
        System.arraycopy(initialSymbols, 0, symbols, 0, symbols.length);
    }

    public UUID playerId() {
        return playerId;
    }

    public CasinoConfig.Machine machine() {
        return machine;
    }

    public Inventory inventory() {
        return inventory;
    }

    public State state() {
        return state;
    }

    public void state(State state) {
        this.state = state;
    }

    public int betIndex() {
        return betIndex;
    }

    public void betIndex(int betIndex) {
        this.betIndex = betIndex;
    }

    public int lineIndex() {
        return lineIndex;
    }

    public void lineIndex(int lineIndex) {
        this.lineIndex = lineIndex;
    }

    public String symbol(int index) {
        return symbols[index];
    }

    public void symbol(int index, String symbol) {
        symbols[index] = symbol;
    }

    public String[] symbolsCopy() {
        return Arrays.copyOf(symbols, symbols.length);
    }

    public boolean columnStopped(int column) {
        return stoppedColumns[column];
    }

    public int stoppedColumnCount() {
        return stoppedColumnCount;
    }

    public int stopNextColumn() {
        for (int column = 0; column < stoppedColumns.length; column++) {
            if (!stoppedColumns[column]) {
                stoppedColumns[column] = true;
                stoppedColumnCount++;
                return column;
            }
        }
        return -1;
    }

    public boolean allColumnsStopped() {
        return stoppedColumnCount >= stoppedColumns.length;
    }

    public void resetStoppedColumns() {
        Arrays.fill(stoppedColumns, false);
        stoppedColumnCount = 0;
    }

    public BukkitTask rollTask() {
        return rollTask;
    }

    public void rollTask(BukkitTask rollTask) {
        this.rollTask = rollTask;
    }

    public boolean ending() {
        return ending;
    }

    public void ending(boolean ending) {
        this.ending = ending;
    }

    public boolean suppressCloseReopen() {
        return suppressCloseReopen;
    }

    public void suppressCloseReopen(boolean suppressCloseReopen) {
        this.suppressCloseReopen = suppressCloseReopen;
    }

    public Location lockedLocation() {
        return lockedLocation;
    }

    public void lockedLocation(Location lockedLocation) {
        this.lockedLocation = lockedLocation;
    }
}
