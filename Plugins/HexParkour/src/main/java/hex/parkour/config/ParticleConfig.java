package hex.parkour.config;

import org.bukkit.Particle;

public record ParticleConfig(boolean enabled, Particle particle, int red, int green, int blue, float size, int density, long intervalTicks) {
}
