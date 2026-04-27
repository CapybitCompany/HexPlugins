package hex.core.api.region;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class Region {
    private final RegionKey key;
    private final String world;
    private final BlockPos min;
    private final BlockPos max;
    private final Map<String, String> meta;

    public Region(RegionKey key, String world, BlockPos min, BlockPos max, Map<String, String> meta) {
        this.key = Objects.requireNonNull(key, "key");
        this.world = Objects.requireNonNull(world, "world");
        this.min = Objects.requireNonNull(min, "min");
        this.max = Objects.requireNonNull(max, "max");
        this.meta = meta == null ? new HashMap<>() : new HashMap<>(meta);
    }

    public RegionKey key() { return key; }
    public String world() { return world; }
    public BlockPos min() { return min; }
    public BlockPos max() { return max; }
    public Map<String, String> meta() { return meta; }

    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        if (!loc.getWorld().getName().equals(world)) return false;

        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        return x >= min.x() && x <= max.x()
                && y >= min.y() && y <= max.y()
                && z >= min.z() && z <= max.z();
    }

    public Location centerLocation() {
        World w = Bukkit.getWorld(world);
        if (w == null) return null;

        double cx = (min.x() + max.x()) / 2.0 + 0.5;
        double cy = (min.y() + max.y()) / 2.0 + 0.0;
        double cz = (min.z() + max.z()) / 2.0 + 0.5;
        return new Location(w, cx, cy, cz);
    }
}