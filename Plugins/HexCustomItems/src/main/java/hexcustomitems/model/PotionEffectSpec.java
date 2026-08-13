package hexcustomitems.model;

import org.bukkit.potion.PotionEffectType;

import java.util.Objects;

public record PotionEffectSpec(
        PotionEffectType type,
        int durationSeconds,
        int amplifier
) {
    public PotionEffectSpec {
        type = Objects.requireNonNull(type, "type");
        durationSeconds = Math.max(1, durationSeconds);
        amplifier = Math.max(0, amplifier);
    }
}
