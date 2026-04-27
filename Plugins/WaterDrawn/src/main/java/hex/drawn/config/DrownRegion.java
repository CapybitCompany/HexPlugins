package hex.drawn.config;

import org.bukkit.Location;

public record DrownRegion(String world, int x1, int z1, int x2, int z2, int yMin, int yMax) {

    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        if (!location.getWorld().getName().equalsIgnoreCase(world)) {
            return false;
        }

        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        return x >= x1 && x <= x2
                && z >= z1 && z <= z2
                && y >= yMin && y <= yMax;
    }
}

