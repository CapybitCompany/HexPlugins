package hex.minigames.game.common;

import hex.minigames.config.ConfiguredSound;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GameSettings {
    private GameSettings() {
    }

    public static Object child(Object raw, String path) {
        if (raw == null || path == null || path.isBlank()) return raw;
        Object current = raw;
        for (String part : path.split("\\.")) {
            if (current == null) return null;
            current = directChild(current, part);
        }
        return current;
    }

    public static Object directChild(Object raw, String key) {
        if (raw instanceof ConfigurationSection section) return section.get(key);
        if (raw instanceof Map<?, ?> map) return map.get(key);
        return null;
    }

    public static String string(Object settings, String path, String fallback) {
        Object value = child(settings, path);
        return value == null ? fallback : String.valueOf(value);
    }

    public static boolean bool(Object settings, String path, boolean fallback) {
        Object value = child(settings, path);
        if (value instanceof Boolean bool) return bool;
        if (value == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public static int integer(Object settings, String path, int fallback) {
        return intValue(child(settings, path), fallback);
    }

    public static long longValue(Object settings, String path, long fallback) {
        Object raw = child(settings, path);
        if (raw instanceof Number number) return number.longValue();
        if (raw == null) return fallback;
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static double decimal(Object settings, String path, double fallback) {
        return doubleValue(child(settings, path), fallback);
    }

    public static int intValue(Object raw, int fallback) {
        if (raw instanceof Number number) return number.intValue();
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static double doubleValue(Object raw, double fallback) {
        if (raw instanceof Number number) return number.doubleValue();
        if (raw == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static Material material(Object settings, String path, Material fallback, String source, List<String> errors) {
        String value = string(settings, path, fallback.name());
        Material material = Material.matchMaterial(value);
        if (material == null) {
            if (errors != null) errors.add(source + ": settings." + path + " is not a valid Bukkit Material: " + value);
            return fallback;
        }
        return material;
    }

    public static BarColor barColor(Object settings, String path, BarColor fallback, String source, List<String> errors) {
        String value = string(settings, path, fallback.name());
        try {
            return BarColor.valueOf(value);
        } catch (IllegalArgumentException error) {
            if (errors != null) errors.add(source + ": settings." + path + " is not a valid BossBar color: " + value);
            return fallback;
        }
    }

    public static BarStyle barStyle(Object settings, String path, BarStyle fallback, String source, List<String> errors) {
        String value = string(settings, path, fallback.name());
        try {
            return BarStyle.valueOf(value);
        } catch (IllegalArgumentException error) {
            if (errors != null) errors.add(source + ": settings." + path + " is not a valid BossBar style: " + value);
            return fallback;
        }
    }

    public static ConfiguredSound sound(Object settings, String path, boolean defaultEnabled, String defaultSound, float defaultVolume, float defaultPitch, String source, List<String> errors) {
        Object raw = child(settings, path);
        boolean enabled = raw == null ? defaultEnabled : bool(raw, "enabled", defaultEnabled);
        String sound = raw == null ? defaultSound : string(raw, "sound", defaultSound);
        float volume = (float) (raw == null ? defaultVolume : decimal(raw, "volume", defaultVolume));
        float pitch = (float) (raw == null ? defaultPitch : decimal(raw, "pitch", defaultPitch));
        ConfiguredSound configured = new ConfiguredSound(enabled, sound, volume, pitch);
        if (!configured.validSound() && errors != null) {
            errors.add(source + ": settings." + path + ".sound is not a valid Bukkit Sound: " + configured.sound());
        }
        return configured;
    }

    public static List<String> stringList(Object raw, List<String> fallback) {
        if (!(raw instanceof List<?> list)) return List.copyOf(fallback);
        List<String> out = new ArrayList<>();
        for (Object item : list) out.add(String.valueOf(item));
        return out;
    }

    public static List<?> list(Object raw) {
        return raw instanceof List<?> list ? list : List.of();
    }

    public static BlockPosition block(Object raw) {
        if (raw == null) return null;
        Object x = child(raw, "x");
        Object y = child(raw, "y");
        Object z = child(raw, "z");
        if (x == null || y == null || z == null) return null;
        return new BlockPosition(intValue(x, 0), intValue(y, 0), intValue(z, 0));
    }

    public static LocationSpec location(Object raw) {
        if (raw == null) return LocationSpec.missing();
        Object x = child(raw, "x");
        Object y = child(raw, "y");
        Object z = child(raw, "z");
        if (x == null || y == null || z == null) return LocationSpec.missing();
        return new LocationSpec(
                doubleValue(x, 0.0),
                doubleValue(y, 0.0),
                doubleValue(z, 0.0),
                (float) doubleValue(child(raw, "yaw"), 0.0),
                (float) doubleValue(child(raw, "pitch"), 0.0),
                true
        );
    }

    public static CuboidRegion region(Object raw, String worldName) {
        BlockPosition pos1 = block(child(raw, "pos1"));
        BlockPosition pos2 = block(child(raw, "pos2"));
        if (pos1 == null || pos2 == null) return null;
        return new CuboidRegion(worldName, pos1, pos2);
    }

    public static List<BlockPosition> blocks(Object raw) {
        List<BlockPosition> out = new ArrayList<>();
        for (Object entry : list(raw)) {
            BlockPosition block = block(entry);
            if (block != null) out.add(block);
        }
        return out;
    }
}
