package hexbuildbattle.config;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Optional;

public record SoundSetting(Optional<Sound> sound, float volume, float pitch) {

    public void play(Player player) {
        if (player == null) {
            return;
        }
        sound.ifPresent(value -> player.playSound(player.getLocation(), value, volume, pitch));
    }
}
