package hex.minions.energy;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

public record BlockPos(String world, int x, int y, int z) {
    public static BlockPos of(Block block) { return new BlockPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()); }
    public static BlockPos of(Location loc) { return new BlockPos(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()); }
    public Block block(World worldObj) { return worldObj.getBlockAt(x, y, z); }
    public BlockPos relative(BlockFace face) { return new BlockPos(world, x + face.getModX(), y + face.getModY(), z + face.getModZ()); }
}
