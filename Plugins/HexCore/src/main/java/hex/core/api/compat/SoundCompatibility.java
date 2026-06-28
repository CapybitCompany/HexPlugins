package hex.core.api.compat;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Resolves Bukkit sounds without compiling static references to org.bukkit.Sound.
 *
 * <p>Paper/Purpur changed Sound from an enum to an interface in newer API lines.
 * Plugins compiled against the newer API can crash on older 1.21 servers when bytecode
 * uses Sound constants or Sound.valueOf directly. Reflection keeps the bytecode neutral.</p>
 */
public final class SoundCompatibility {
    private SoundCompatibility() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Sound resolve(String enumName) {
        if (enumName == null || enumName.isBlank()) {
            return null;
        }

        String normalized = enumName.trim().toUpperCase(Locale.ROOT);
        try {
            Class<?> soundClass = Class.forName("org.bukkit.Sound");
            Object sound;
            if (soundClass.isEnum()) {
                sound = Enum.valueOf((Class<? extends Enum>) soundClass.asSubclass(Enum.class), normalized);
            } else {
                Method valueOf = soundClass.getMethod("valueOf", String.class);
                sound = valueOf.invoke(null, normalized);
            }
            return (Sound) sound;
        } catch (ReflectiveOperationException | IllegalArgumentException | ClassCastException | LinkageError ignored) {
            return null;
        }
    }

    public static void play(Player player, Location location, String enumName, float volume, float pitch) {
        if (player == null || location == null || enumName == null || enumName.isBlank()) {
            return;
        }

        Sound sound = resolve(enumName);
        if (sound != null) {
            player.playSound(location, sound, volume, pitch);
            return;
        }

        String key = toMinecraftKey(enumName);
        if (key != null) {
            player.playSound(location, key, volume, pitch);
        }
    }

    public static String toMinecraftKey(String enumName) {
        if (enumName == null || enumName.isBlank()) {
            return null;
        }
        String normalized = enumName.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains(":")) {
            return normalized;
        }
        if (normalized.indexOf('.') >= 0) {
            return normalized;
        }

        String[] parts = normalized.split("_");
        if (parts.length < 2) {
            return NamespacedKey.minecraft(normalized).asString();
        }
        StringBuilder key = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            key.append('.').append(parts[i]);
        }
        return NamespacedKey.minecraft(key.toString()).asString();
    }
}
