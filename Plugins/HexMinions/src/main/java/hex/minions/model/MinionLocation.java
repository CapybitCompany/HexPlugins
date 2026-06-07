package hex.minions.model;

import org.bukkit.Location;

public record MinionLocation(String world, int x, int y, int z, float yaw) {
    public static MinionLocation from(Location location) {
        return new MinionLocation(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), location.getYaw());
    }

    public String compact() {
        return world + " " + x + "," + y + "," + z;
    }
}

