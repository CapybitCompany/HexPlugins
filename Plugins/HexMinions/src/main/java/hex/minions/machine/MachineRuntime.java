package hex.minions.machine;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

public final class MachineRuntime {
    private final String blockKey;
    private String machineId;
    private ItemStack input;
    private final ItemStack[] extraInputs = new ItemStack[2];
    private ItemStack secondary;
    private ItemStack fuel;
    private ItemStack output;
    private ItemStack output2;
    private final ItemStack[] upgrades = new ItemStack[3];
    private int energy;
    private String recipeId = "";
    private final String[] extraRecipeIds = new String[]{""};
    private int progressSeconds;
    private final int[] extraProgressSeconds = new int[1];
    private int lastFuelSeconds;
    private int burnRemainingSeconds;
    private int burnEuRemaining;
    private long lastActiveAtMillis = System.currentTimeMillis();
    private String accumulatorInputFace = "";

    public MachineRuntime(String blockKey, String machineId) {
        this.blockKey = blockKey;
        this.machineId = machineId == null ? "" : machineId.toLowerCase(java.util.Locale.ROOT);
    }

    public String blockKey() { return blockKey; }
    public String machineId() { return machineId; }
    public void machineId(String machineId) { this.machineId = machineId == null ? "" : machineId.toLowerCase(java.util.Locale.ROOT); }
    public ItemStack input() { return clone(input); }
    public void input(ItemStack input) { this.input = clone(input); }
    public ItemStack extraInput(int index) { return index >= 0 && index < extraInputs.length ? clone(extraInputs[index]) : null; }
    public void extraInput(int index, ItemStack item) { if (index >= 0 && index < extraInputs.length) extraInputs[index] = clone(item); }
    public ItemStack inputAt(int index) {
        if (index <= 0) return input();
        return extraInput(index - 1);
    }
    public void inputAt(int index, ItemStack item) {
        if (index <= 0) input(item);
        else extraInput(index - 1, item);
    }
    public ItemStack secondary() { return clone(secondary); }
    public void secondary(ItemStack secondary) { this.secondary = clone(secondary); }
    public ItemStack fuel() { return clone(fuel); }
    public void fuel(ItemStack fuel) { this.fuel = clone(fuel); }
    public ItemStack output() { return clone(output); }
    public void output(ItemStack output) { this.output = clone(output); }
    public ItemStack output2() { return clone(output2); }
    public void output2(ItemStack output2) { this.output2 = clone(output2); }
    public ItemStack outputAt(int index) {
        return index <= 0 ? output() : output2();
    }
    public void outputAt(int index, ItemStack item) {
        if (index <= 0) output(item);
        else output2(item);
    }
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
    public String recipeIdAt(int index) { return index <= 0 ? recipeId : extraRecipeIds[Math.min(index - 1, extraRecipeIds.length - 1)]; }
    public int progressSeconds() { return progressSeconds; }
    public int progressSecondsAt(int index) { return index <= 0 ? progressSeconds : extraProgressSeconds[Math.min(index - 1, extraProgressSeconds.length - 1)]; }
    public int lastFuelSeconds() { return lastFuelSeconds; }
    public int burnRemainingSeconds() { return burnRemainingSeconds; }
    public int burnEuRemaining() { return burnEuRemaining; }
    public long lastActiveAtMillis() { return lastActiveAtMillis; }
    public void lastActiveAtMillis(long value) { this.lastActiveAtMillis = Math.max(0L, value); }
    public void touchActiveNow() { this.lastActiveAtMillis = System.currentTimeMillis(); }
    public String accumulatorInputFace() { return accumulatorInputFace; }
    public void accumulatorInputFace(String face) { this.accumulatorInputFace = face == null ? "" : face.toUpperCase(java.util.Locale.ROOT); }

    public void resetProcess() { resetProcessAt(0); }

    public void resetProcessAt(int index) {
        if (index <= 0) {
            recipeId = "";
            progressSeconds = 0;
        } else if (index - 1 < extraRecipeIds.length) {
            extraRecipeIds[index - 1] = "";
            extraProgressSeconds[index - 1] = 0;
        }
    }

    public void startProcess(String recipeId) { startProcessAt(0, recipeId); }

    public void startProcessAt(int index, String recipeId) {
        if (index <= 0) {
            this.recipeId = recipeId == null ? "" : recipeId;
            this.progressSeconds = 0;
        } else if (index - 1 < extraRecipeIds.length) {
            extraRecipeIds[index - 1] = recipeId == null ? "" : recipeId;
            extraProgressSeconds[index - 1] = 0;
        }
    }

    public void restoreProcess(String recipeId, int progressSeconds) { restoreProcessAt(0, recipeId, progressSeconds); }

    public void restoreProcessAt(int index, String recipeId, int progressSeconds) {
        if (index <= 0) {
            this.recipeId = recipeId == null ? "" : recipeId;
            this.progressSeconds = Math.max(0, progressSeconds);
        } else if (index - 1 < extraRecipeIds.length) {
            extraRecipeIds[index - 1] = recipeId == null ? "" : recipeId;
            extraProgressSeconds[index - 1] = Math.max(0, progressSeconds);
        }
    }

    public void addProgressSecond() { addProgressSecondAt(0); }
    public void addProgressSecondAt(int index) {
        if (index <= 0) progressSeconds++;
        else if (index - 1 < extraProgressSeconds.length) extraProgressSeconds[index - 1]++;
    }

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

    public void consumeInput(int amount) { consumeInputAt(0, amount); }
    public void consumeInputAt(int index, int amount) {
        if (index <= 0) input = consume(input, amount);
        else if (index - 1 >= 0 && index - 1 < extraInputs.length) extraInputs[index - 1] = consume(extraInputs[index - 1], amount);
    }
    public void consumeSecondary(int amount) { secondary = consume(secondary, amount); }
    public void consumeRecipeFuel(int amount) { fuel = consume(fuel, amount); }

    public void drop(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        for (ItemStack item : new ItemStack[]{input, extraInputs[0], extraInputs[1], secondary, fuel, output, output2, upgrades[0], upgrades[1], upgrades[2]}) {
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
