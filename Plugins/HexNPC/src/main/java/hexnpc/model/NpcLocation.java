package hexnpc.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

public record NpcLocation(
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
    public NpcLocation {
        world = Objects.requireNonNull(world, "world");
        if (world.isBlank()) {
            throw new IllegalArgumentException("world is blank");
        }
    }

    public static NpcLocation fromBukkit(Location location) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(location.getWorld(), "location.world");
        return new NpcLocation(
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch()
        );
    }

    public Location toBukkit() {
        World w = Bukkit.getWorld(world);
        if (w == null) {
            return null;
        }
        return new Location(w, x, y, z, yaw, pitch);
    }

    public NpcLocation withRotation(float newYaw, float newPitch) {
        return new NpcLocation(world, x, y, z, newYaw, newPitch);
    }

    public NpcLocation withPosition(String newWorld, double nx, double ny, double nz, float nyaw, float npitch) {
        return new NpcLocation(newWorld, nx, ny, nz, nyaw, npitch);
    }
}
