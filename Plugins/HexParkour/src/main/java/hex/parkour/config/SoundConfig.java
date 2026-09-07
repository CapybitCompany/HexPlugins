package hex.parkour.config;

import org.bukkit.Sound;

public record SoundConfig(boolean enabled, Sound sound, float volume, float pitch) {
}
