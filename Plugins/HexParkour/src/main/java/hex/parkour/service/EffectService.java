package hex.parkour.service;

import hex.parkour.config.ParticleConfig;
import hex.parkour.config.SoundConfig;
import hex.parkour.model.CuboidRegion;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class EffectService {
    public void playSound(Player player, SoundConfig config) {
        if (config == null || !config.enabled() || config.sound() == null) return;
        player.playSound(player.getLocation(), config.sound(), config.volume(), config.pitch());
    }

    public void spawnRegionMarker(Player player, String worldName, CuboidRegion region, ParticleConfig config) {
        if (config == null || !config.enabled() || config.particle() == null) return;
        World world = player.getWorld();
        if (!world.getName().equals(worldName)) return;
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(config.red(), config.green(), config.blue()), config.size());
        int count = Math.max(1, config.density());
        for (int i = 0; i < count; i++) {
            double t = count == 1 ? 0.5 : (double) i / (double) (count - 1);
            double x = lerp(region.minX() + 0.5, region.maxX() + 0.5, t);
            double z = lerp(region.minZ() + 0.5, region.maxZ() + 0.5, t);
            double y = region.maxY() - 0.35;
            Location location = new Location(world, x, y, z);
            if (config.particle() == Particle.DUST) {
                player.spawnParticle(config.particle(), location, 1, 0, 0, 0, 0, dust);
            } else {
                player.spawnParticle(config.particle(), location, 1, 0, 0, 0, 0);
            }
        }
    }

    private double lerp(double min, double max, double t) {
        return min + (max - min) * t;
    }
}
