package hex.minigames.framework.config;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public record LobbySpawn(String world, double x, double y, double z, float yaw, float pitch) {

    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        if (w == null) {
            throw new IllegalStateException("World not loaded: " + world);
        }
        return new Location(w, x, y, z, yaw, pitch);
    }

    public static LobbySpawn parse(String raw) {
        String[] p = raw.split(",");
        if (p.length != 6) {
            throw new IllegalArgumentException("Expected lobbySpawn as world,x,y,z,yaw,pitch");
        }

        return new LobbySpawn(
                p[0].trim(),
                Double.parseDouble(p[1].trim()),
                Double.parseDouble(p[2].trim()),
                Double.parseDouble(p[3].trim()),
                Float.parseFloat(p[4].trim()),
                Float.parseFloat(p[5].trim())
        );
    }
}

