package hex.minigames.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record LocationSpec(double x, double y, double z, float yaw, float pitch, boolean configured) {
    public static LocationSpec missing() {
        return new LocationSpec(0.0, 0.0, 0.0, 0.0f, 0.0f, false);
    }

    public Location toLocation(String worldName) {
        if (!configured) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, x, y, z, yaw, pitch);
    }
}
