package hexcasino.machine;

import hexcasino.config.CasinoConfig;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BusDriverSession {

    public enum State {
        IDLE,
        PLAYING,
        SHOWING_RESULT
    }

    private final UUID playerId;
    private final CasinoConfig.Machine machine;
    private final Inventory inventory;

    private State state = State.IDLE;
    private int multiplierIndex;
    private BusDriverService.Card currentCard;
    private int completedRounds;
    private double stake;
    private double currentWin;
    private boolean ending;
    private boolean suppressCloseReopen;
    private boolean actionLocked;
    private Location lockedLocation;
    private final List<BusDriverService.Card> cards = new ArrayList<>();

    public BusDriverSession(UUID playerId, CasinoConfig.Machine machine, Inventory inventory, int multiplierIndex) {
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

    public BusDriverService.Card currentCard() {
        return currentCard;
    }

    public void currentCard(BusDriverService.Card currentCard) {
        this.currentCard = currentCard;
    }

    public int completedRounds() {
        return completedRounds;
    }

    public void completedRounds(int completedRounds) {
        this.completedRounds = completedRounds;
    }

    public double stake() {
        return stake;
    }

    public void stake(double stake) {
        this.stake = stake;
    }

    public double currentWin() {
        return currentWin;
    }

    public void currentWin(double currentWin) {
        this.currentWin = currentWin;
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

    public boolean actionLocked() {
        return actionLocked;
    }

    public void actionLocked(boolean actionLocked) {
        this.actionLocked = actionLocked;
    }

    public Location lockedLocation() {
        return lockedLocation;
    }

    public void lockedLocation(Location lockedLocation) {
        this.lockedLocation = lockedLocation;
    }

    public void clearCards() {
        cards.clear();
        currentCard = null;
    }

    public void addCard(BusDriverService.Card card) {
        cards.add(card);
        currentCard = card;
    }

    public BusDriverService.Card card(int index) {
        return index >= 0 && index < cards.size() ? cards.get(index) : null;
    }

    public List<BusDriverService.Card> cards() {
        return List.copyOf(cards);
    }
}
