package hex.minions.robot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class MiningRobot {
    private final UUID id;
    private final UUID ownerId;
    private UUID townId;
    private String world;
    private int x;
    private int y;
    private int z;
    private BlockFace facing;
    private boolean active;
    private int fuelSecondsRemaining;
    private long nextWorkTick;
    private final ItemStack[] upgrades = new ItemStack[3];
    private ItemStack storageUpgrade;
    private ItemStack fuel;
    private ItemStack pickaxe;
    private final ItemStack[] storage = new ItemStack[12];

    public MiningRobot(UUID id, UUID ownerId, UUID townId, Location location, BlockFace facing) {
        this.id = id;
        this.ownerId = ownerId;
        this.townId = townId;
        this.world = location.getWorld().getName();
        this.x = location.getBlockX();
        this.y = location.getBlockY();
        this.z = location.getBlockZ();
        this.facing = normalizeFacing(facing);
        this.active = false;
        this.fuelSecondsRemaining = 0;
        this.nextWorkTick = 0L;
    }

    public UUID id() { return id; }
    public UUID ownerId() { return ownerId; }
    public UUID townId() { return townId; }
    public void setTownId(UUID townId) { this.townId = townId; }
    public String worldName() { return world; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public BlockFace facing() { return facing; }
    public boolean active() { return active; }
    public int fuelSecondsRemaining() { return fuelSecondsRemaining; }
    public long nextWorkTick() { return nextWorkTick; }
    public ItemStack[] upgrades() { return upgrades; }
    public ItemStack storageUpgrade() { return storageUpgrade; }
    public ItemStack fuel() { return fuel; }
    public ItemStack pickaxe() { return pickaxe; }
    public ItemStack[] storage() { return storage; }

    public void setActive(boolean active) { this.active = active; }
    public void setFuelSecondsRemaining(int fuelSecondsRemaining) { this.fuelSecondsRemaining = Math.max(0, fuelSecondsRemaining); }
    public void setNextWorkTick(long nextWorkTick) { this.nextWorkTick = nextWorkTick; }
    public void setStorageUpgrade(ItemStack storageUpgrade) { this.storageUpgrade = storageUpgrade; }
    public void setFuel(ItemStack fuel) { this.fuel = fuel; }
    public void setPickaxe(ItemStack pickaxe) { this.pickaxe = pickaxe; }

    public Location location() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z);
    }

    public Location centerLocation() {
        Location loc = location();
        return loc == null ? null : loc.add(0.5, 0.5, 0.5);
    }

    public void moveTo(Location location, BlockFace facing) {
        this.world = location.getWorld().getName();
        this.x = location.getBlockX();
        this.y = location.getBlockY();
        this.z = location.getBlockZ();
        this.facing = normalizeFacing(facing);
    }

    public void stopAndResetFuelUse() {
        this.active = false;
        this.fuelSecondsRemaining = 0;
        this.nextWorkTick = 0L;
    }

    private static BlockFace normalizeFacing(BlockFace face) {
        if (face == BlockFace.UP || face == BlockFace.DOWN || face == BlockFace.NORTH || face == BlockFace.EAST || face == BlockFace.SOUTH || face == BlockFace.WEST) return face;
        return BlockFace.NORTH;
    }
}
