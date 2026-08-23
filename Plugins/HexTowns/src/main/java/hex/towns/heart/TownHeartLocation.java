package hex.towns.heart;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

public record TownHeartLocation(UUID townId, String world, int x, int y, int z, int chunkX, int chunkZ) {
    public Location toLocation() {
        World bukkitWorld = Bukkit.getWorld(world);
        return bukkitWorld == null ? null : new Location(bukkitWorld, x + 0.5, y, z + 0.5);
    }

    public boolean sameBlock(Location location) {
        return location != null && location.getWorld() != null
                && location.getWorld().getName().equals(world)
                && location.getBlockX() == x
                && location.getBlockY() == y
                && location.getBlockZ() == z;
    }

    public boolean inHeartChunk(Location location) {
        return location != null && location.getWorld() != null
                && location.getWorld().getName().equals(world)
                && (location.getBlockX() >> 4) == chunkX
                && (location.getBlockZ() >> 4) == chunkZ;
    }

    /**
     * Only the immediate 3x3x3 cube around the visible heart is protected from
     * ordinary building. The 3x3 bedrock foundation one block below that cube
     * is protected separately.
     */
    public boolean inProtectedBuildZone(Location location) {
        return inHeartCube(location) || inBedrockFoundation(location);
    }

    public boolean inHeartCube(Location location) {
        return location != null && location.getWorld() != null
                && location.getWorld().getName().equals(world)
                && location.getBlockX() >= x - 1
                && location.getBlockX() <= x + 1
                && location.getBlockY() >= y - 1
                && location.getBlockY() <= y + 1
                && location.getBlockZ() >= z - 1
                && location.getBlockZ() <= z + 1;
    }

    public boolean inBedrockFoundation(Location location) {
        return location != null && location.getWorld() != null
                && location.getWorld().getName().equals(world)
                && location.getBlockY() == y - 2
                && location.getBlockX() >= x - 1
                && location.getBlockX() <= x + 1
                && location.getBlockZ() >= z - 1
                && location.getBlockZ() <= z + 1;
    }
}
