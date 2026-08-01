package hexcasino.machine;

import hexcasino.config.CasinoConfig;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public final class WheelSession {

    public enum State {
        IDLE,
        SPINNING,
        SHOWING_RESULT
    }

    private final UUID playerId;
    private final CasinoConfig.Machine machine;
    private final Inventory inventory;

    private State state = State.IDLE;
    private int multiplierIndex;
    private int wheelIndex;
    private BukkitTask spinTask;
    private boolean ending;
    private boolean suppressCloseReopen;
    private Location lockedLocation;

    public WheelSession(UUID playerId, CasinoConfig.Machine machine, Inventory inventory, int multiplierIndex) {
        this.playerId = playerId;
        this.machine = machine;
        this.inventory = inventory;
        this.multiplierIndex = multiplierIndex;
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

    public int multiplierIndex() {
        return multiplierIndex;
    }

    public void multiplierIndex(int multiplierIndex) {
        this.multiplierIndex = multiplierIndex;
    }

    public int wheelIndex() {
        return wheelIndex;
    }

    public void wheelIndex(int wheelIndex) {
        this.wheelIndex = wheelIndex;
    }

    public BukkitTask spinTask() {
        return spinTask;
    }

    public void spinTask(BukkitTask spinTask) {
        this.spinTask = spinTask;
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
