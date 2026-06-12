package hex.minions.machine;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

public final class MachineRuntime {
    private final String blockKey;
    private String machineId;
    private ItemStack input;
    private ItemStack secondary;
    private ItemStack fuel;
    private ItemStack output;
    private final ItemStack[] upgrades = new ItemStack[3];
    private int energy;
    private String recipeId = "";
    private int progressSeconds;
    private int lastFuelSeconds;
    private int burnRemainingSeconds;
    private int burnEuRemaining;

    public MachineRuntime(String blockKey, String machineId) {
        this.blockKey = blockKey;
        this.machineId = machineId == null ? "" : machineId.toLowerCase(java.util.Locale.ROOT);
    }

    public String blockKey() { return blockKey; }
    public String machineId() { return machineId; }
    public void machineId(String machineId) { this.machineId = machineId == null ? "" : machineId.toLowerCase(java.util.Locale.ROOT); }
    public ItemStack input() { return clone(input); }
    public void input(ItemStack input) { this.input = clone(input); }
    public ItemStack secondary() { return clone(secondary); }
    public void secondary(ItemStack secondary) { this.secondary = clone(secondary); }
    public ItemStack fuel() { return clone(fuel); }
    public void fuel(ItemStack fuel) { this.fuel = clone(fuel); }
    public ItemStack output() { return clone(output); }
    public void output(ItemStack output) { this.output = clone(output); }
    public ItemStack upgrade(int index) { return index >= 0 && index < upgrades.length ? clone(upgrades[index]) : null; }
    public void upgrade(int index, ItemStack item) { if (index >= 0 && index < upgrades.length) upgrades[index] = clone(item); }
    public ItemStack[] upgradesCopy() {
        ItemStack[] copy = new ItemStack[upgrades.length];
        for (int i = 0; i < upgrades.length; i++) copy[i] = clone(upgrades[i]);
        return copy;
    }

    public int energy() { return energy; }
    public void energy(int energy) { this.energy = Math.max(0, energy); }
    public void addEnergy(int amount, int capacity) { this.energy = Math.min(Math.max(0, capacity), this.energy + Math.max(0, amount)); }
    public boolean consumeEnergy(int amount) {
        if (amount <= 0) return true;
        if (energy < amount) return false;
        energy -= amount;
        return true;
    }

    public String recipeId() { return recipeId; }
    public int progressSeconds() { return progressSeconds; }
    public int lastFuelSeconds() { return lastFuelSeconds; }
    public int burnRemainingSeconds() { return burnRemainingSeconds; }
    public int burnEuRemaining() { return burnEuRemaining; }

    public void resetProcess() {
        recipeId = "";
        progressSeconds = 0;
    }

    public void startProcess(String recipeId) {
        this.recipeId = recipeId == null ? "" : recipeId;
        this.progressSeconds = 0;
    }

    public void restoreProcess(String recipeId, int progressSeconds) {
        this.recipeId = recipeId == null ? "" : recipeId;
        this.progressSeconds = Math.max(0, progressSeconds);
    }

    public void addProgressSecond() { progressSeconds++; }

    public void startBurn(int eu, int seconds) {
        this.burnEuRemaining = Math.max(0, eu);
        this.burnRemainingSeconds = Math.max(1, seconds);
        this.lastFuelSeconds = Math.max(1, seconds);
    }

    public void restoreBurn(int euRemaining, int secondsRemaining, int lastFuelSeconds) {
        this.burnEuRemaining = Math.max(0, euRemaining);
        this.burnRemainingSeconds = Math.max(0, secondsRemaining);
        this.lastFuelSeconds = Math.max(0, lastFuelSeconds);
    }

    public void stopBurn() {
        this.burnEuRemaining = 0;
        this.burnRemainingSeconds = 0;
    }

    public void burnTick(int capacity) {
        if (burnRemainingSeconds <= 0 || burnEuRemaining <= 0) {
            stopBurn();
            return;
        }
        if (energy >= capacity) return;
        int euThisSecond = Math.max(1, (int) Math.ceil(burnEuRemaining / (double) burnRemainingSeconds));
        int accepted = Math.min(euThisSecond, Math.max(0, capacity - energy));
        energy += accepted;
        burnEuRemaining -= euThisSecond;
        burnRemainingSeconds--;
        if (burnRemainingSeconds <= 0 || burnEuRemaining <= 0) stopBurn();
    }

    public int capacity(MachineDefinition machine) {
        if (machine == null || !machine.energy().enabled()) return 0;
        return Math.max(0, machine.energy().bufferCapacity()) + Math.max(0, machine.energy().batteryExtraCapacity());
    }

    public void consumeFuelItem() {
        if (fuel == null) return;
        fuel.setAmount(fuel.getAmount() - 1);
        if (fuel.getAmount() <= 0) fuel = null;
    }

    public void consumeInput(int amount) { input = consume(input, amount); }
    public void consumeSecondary(int amount) { secondary = consume(secondary, amount); }
    public void consumeRecipeFuel(int amount) { fuel = consume(fuel, amount); }

    public void drop(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        for (ItemStack item : new ItemStack[]{input, secondary, fuel, output, upgrades[0], upgrades[1], upgrades[2]}) {
            if (item != null && !item.getType().isAir()) loc.getWorld().dropItemNaturally(loc, item);
        }
    }

    static ItemStack clone(ItemStack item) { return item == null || item.getType().isAir() ? null : item.clone(); }

    private static ItemStack consume(ItemStack item, int amount) {
        if (item == null || item.getType().isAir()) return null;
        ItemStack copy = item.clone();
        copy.setAmount(copy.getAmount() - Math.max(0, amount));
        return copy.getAmount() <= 0 ? null : copy;
    }
}
