package hex.minigames.config;

import org.bukkit.Sound;

public record ConfiguredSound(boolean enabled, String sound, float volume, float pitch) {
    public ConfiguredSound {
        sound = sound == null ? "" : sound;
    }

    public Sound bukkitSound() {
        if (!enabled || sound.isBlank()) return null;
        Sound resolved = SoundResolver.resolve(sound);
        if (resolved == null) {
            throw new IllegalArgumentException("Unknown Bukkit Sound: " + sound);
        }
        return resolved;
    }

    public boolean validSound() {
        if (!enabled) return true;
        return SoundResolver.isValid(sound);
    }
}
