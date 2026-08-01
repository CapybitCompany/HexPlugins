package hexcasino.machine;

import hexcasino.config.CasinoConfig;
import org.bukkit.Location;
import org.bukkit.block.Block;

public record MachineKey(String world, int x, int y, int z) {

    public static MachineKey from(Block block) {
        return new MachineKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public static MachineKey from(String world, CasinoConfig.BlockLocation location) {
        return new MachineKey(world, location.x(), location.y(), location.z());
    }

    public Location center(Location base, double yOffset) {
        return new Location(base.getWorld(), x + 0.5D, y + yOffset, z + 0.5D);
    }
}
