package hexbuildbattle.rating;

import org.bukkit.Material;
import org.bukkit.Particle;

import hexbuildbattle.config.SoundSetting;

import java.util.List;

public record RatingDefinition(
        int level,
        Material material,
        String displayName,
        int points,
        List<SoundSetting> sounds,
        Particle particle
) {
}
