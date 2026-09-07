package hex.parkour.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record LocationSpec(double x, double y, double z, float yaw, float pitch) {
    public Location toLocation(String worldName) {
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : new Location(world, x, y, z, yaw, pitch);
    }
}
