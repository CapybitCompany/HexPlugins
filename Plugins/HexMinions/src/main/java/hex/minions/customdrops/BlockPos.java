package hex.minions.customdrops;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

public record BlockPos(String world, int x, int y, int z) {
    public static BlockPos of(Block block) {
        return new BlockPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public static BlockPos of(Location location) {
        World world = location.getWorld();
        return new BlockPos(world == null ? "" : world.getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public BlockPos relative(BlockFace face) {
        return new BlockPos(world, x + face.getModX(), y + face.getModY(), z + face.getModZ());
    }

    public int packedLocal() {
        int localX = x & 15;
        int localZ = z & 15;
        return localX | (localZ << 4) | ((y + 64) << 8);
    }

    public static BlockPos unpack(ChunkKey key, int packed) {
        int localX = packed & 15;
        int localZ = (packed >> 4) & 15;
        int y = (packed >> 8) - 64;
        return new BlockPos(key.world(), key.chunkX() * 16 + localX, y, key.chunkZ() * 16 + localZ);
    }
}
