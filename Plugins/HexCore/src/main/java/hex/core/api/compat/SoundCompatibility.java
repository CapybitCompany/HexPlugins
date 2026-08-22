package hex.core.api.compat;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Shared sound helper that lets feature plugins reference Bukkit sound names without
 * failing when a sound is unavailable on a particular Minecraft server version.
 */
public final class SoundCompatibility {
    private SoundCompatibility() {
    }

    public static void play(Player player, Location location, String soundName, float volume, float pitch) {
        if (player == null || location == null || soundName == null || soundName.isBlank()) {
            return;
        }

        Sound sound = findSound(soundName);
        if (sound == null) {
            return;
        }

        player.playSound(location, sound, volume, pitch);
    }

    private static Sound findSound(String soundName) {
        try {
            return Sound.valueOf(soundName);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
