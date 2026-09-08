package hexbuildbattle.config;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

public final class ConfigParsers {

    private ConfigParsers() {
    }

    public static Material material(String input, Material fallback, Logger logger, String path) {
        Optional<Material> parsed = material(input);
        if (parsed.isPresent()) {
            return parsed.get();
        }
        logger.warning("Invalid Material '" + input + "' at " + path + ". Using " + fallback.name() + ".");
        return fallback;
    }

    public static Optional<Material> material(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Material.valueOf(normalizeEnumName(input)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public static Optional<Sound> sound(String input, Logger logger, String path) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }
        String raw = input.trim();
        String normalized = normalizeEnumName(raw);
        Optional<Sound> legacyConstant = legacySoundConstant(normalized);
        if (legacyConstant.isPresent()) {
            return legacyConstant;
        }

        String key = raw.toLowerCase(Locale.ROOT);
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        Sound sound = namespacedKey == null ? null : Registry.SOUNDS.get(namespacedKey);
        if (sound != null) {
            return Optional.of(sound);
        }

        key = "minecraft:" + normalized.toLowerCase(Locale.ROOT).replace('_', '.');
        namespacedKey = NamespacedKey.fromString(key);
        sound = namespacedKey == null ? null : Registry.SOUNDS.get(namespacedKey);
        if (sound != null) {
            return Optional.of(sound);
        }
        logger.warning("Invalid Sound '" + input + "' at " + path + ". Sound disabled for this entry.");
        return Optional.empty();
    }

    private static Optional<Sound> legacySoundConstant(String normalized) {
        try {
            Object value = Sound.class.getField(normalized).get(null);
            if (value instanceof Sound sound) {
                return Optional.of(sound);
            }
        } catch (ReflectiveOperationException | SecurityException ignored) {
            // Not a legacy Bukkit/Paper constant; caller will try namespaced keys.
        }
        return Optional.empty();
    }

    public static Particle particle(String input, Particle fallback, Logger logger, String path) {
        if (input == null || input.isBlank()) {
            return fallback;
        }
        try {
            return Particle.valueOf(normalizeEnumName(input));
        } catch (IllegalArgumentException ex) {
            logger.warning("Invalid Particle '" + input + "' at " + path + ". Using " + fallback.name() + ".");
            return fallback;
        }
    }

    public static SoundSetting soundSetting(
            ConfigurationSection section,
            String fallbackSound,
            double fallbackVolume,
            double fallbackPitch,
            Logger logger,
            String path
    ) {
        String soundName = fallbackSound;
        double volume = fallbackVolume;
        double pitch = fallbackPitch;
        if (section != null) {
            soundName = section.getString("sound", fallbackSound);
            volume = section.getDouble("volume", fallbackVolume);
            pitch = section.getDouble("pitch", fallbackPitch);
        }
        return new SoundSetting(
                sound(soundName, logger, path + ".sound"),
                boundedFloat(volume, 0.0D, 10.0D, fallbackVolume),
                boundedFloat(pitch, 0.01D, 2.0D, fallbackPitch)
        );
    }

    public static List<SoundSetting> soundSettingList(
            ConfigurationSection section,
            String path,
            Logger logger,
            double fallbackVolume,
            double fallbackPitch
    ) {
        if (section == null) {
            return List.of();
        }
        List<SoundSetting> sounds = new ArrayList<>();
        List<Map<?, ?>> mapList = section.getMapList(path);
        for (int i = 0; i < mapList.size(); i++) {
            Map<?, ?> entry = mapList.get(i);
            Object soundValue = entry.get("sound");
            String soundName = soundValue == null ? "" : soundValue.toString();
            double volume = doubleValue(entry.get("volume"), fallbackVolume);
            double pitch = doubleValue(entry.get("pitch"), fallbackPitch);
            sounds.add(new SoundSetting(
                    sound(soundName, logger, path + "[" + i + "].sound"),
                    boundedFloat(volume, 0.0D, 10.0D, fallbackVolume),
                    boundedFloat(pitch, 0.01D, 2.0D, fallbackPitch)
            ));
        }

        if (sounds.isEmpty()) {
            for (String soundName : section.getStringList(path)) {
                sounds.add(new SoundSetting(
                        sound(soundName, logger, path + ".sound"),
                        boundedFloat(fallbackVolume, 0.0D, 10.0D, fallbackVolume),
                        boundedFloat(fallbackPitch, 0.01D, 2.0D, fallbackPitch)
                ));
            }
        }
        return sounds;
    }

    public static Set<Material> materialSet(Collection<String> values, Logger logger, String path) {
        Set<Material> materials = new HashSet<>();
        for (String value : values) {
            Optional<Material> parsed = material(value);
            if (parsed.isPresent()) {
                materials.add(parsed.get());
            } else {
                logger.warning("Invalid Material '" + value + "' at " + path + ". Entry ignored.");
            }
        }
        return materials;
    }

    public static String normalizeEnumName(String input) {
        return input.trim()
                .toUpperCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_')
                .replace("MINECRAFT:", "");
    }

    private static float boundedFloat(double value, double min, double max, double fallback) {
        if (Double.isNaN(value) || value < min || value > max) {
            return (float) Math.max(min, Math.min(max, fallback));
        }
        return (float) value;
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
