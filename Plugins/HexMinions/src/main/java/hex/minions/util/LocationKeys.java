package hex.minions.util;

import hex.minions.model.MinionLocation;
import org.bukkit.Chunk;
import org.bukkit.Location;

public final class LocationKeys {
    private LocationKeys() {}

    public static String blockKey(String world, int x, int y, int z) {
        return world + ':' + x + ':' + y + ':' + z;
    }

    public static String blockKey(Location loc) {
        return blockKey(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static String blockKey(MinionLocation loc) {
        return blockKey(loc.world(), loc.x(), loc.y(), loc.z());
    }

    public static String chunkKey(String world, int chunkX, int chunkZ) {
        return world + ':' + chunkX + ':' + chunkZ;
    }

    public static String chunkKey(Chunk chunk) {
        return chunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
    }

    public static String chunkKey(MinionLocation loc) {
        return chunkKey(loc.world(), Math.floorDiv(loc.x(), 16), Math.floorDiv(loc.z(), 16));
    }
}

