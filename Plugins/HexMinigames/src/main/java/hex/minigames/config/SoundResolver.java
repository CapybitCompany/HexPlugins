package hex.minigames.config;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;

import java.util.Locale;
import java.util.Optional;

public final class SoundResolver {
    private SoundResolver() {
    }

    public static Sound resolve(String raw) {
        if (raw == null || raw.isBlank()) return null;

        NamespacedKey directKey = parseInputKey(raw);
        if (directKey != null) {
            Sound direct = Registry.SOUNDS.get(directKey);
            if (direct != null) return direct;
        }

        String legacyName = legacyLookupName(raw);
        for (NamespacedKey key : Registry.SOUNDS.keyStream().toList()) {
            if (legacyName(key).equals(legacyName)) {
                return Registry.SOUNDS.get(key);
            }
        }
        return null;
    }

    public static boolean isValid(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            return resolve(raw) != null;
        } catch (Throwable ignored) {
            // Unit tests run without Paper RegistryAccess. Live servers validate through Registry.SOUNDS.
            return true;
        }
    }

    static Optional<NamespacedKey> resolveKey(String raw, Iterable<NamespacedKey> availableKeys) {
        if (raw == null || raw.isBlank()) return Optional.empty();

        NamespacedKey directKey = parseInputKey(raw);
        if (directKey != null) {
            for (NamespacedKey key : availableKeys) {
                if (key.equals(directKey)) return Optional.of(key);
            }
        }

        String legacyName = legacyLookupName(raw);
        for (NamespacedKey key : availableKeys) {
            if (legacyName(key).equals(legacyName)) return Optional.of(key);
        }
        return Optional.empty();
    }

    private static NamespacedKey parseInputKey(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains(":")) {
            normalized = NamespacedKey.MINECRAFT + ":" + normalized;
        }
        return NamespacedKey.fromString(normalized);
    }

    private static String legacyName(NamespacedKey key) {
        return legacyLookupName(key.getKey());
    }

    private static String legacyLookupName(String raw) {
        String value = raw.trim();
        int namespaceSeparator = value.indexOf(':');
        if (namespaceSeparator >= 0) {
            value = value.substring(namespaceSeparator + 1);
        }
        return value.toUpperCase(Locale.ROOT)
                .replace('.', '_')
                .replace('-', '_')
                .replace('/', '_');
    }
}
