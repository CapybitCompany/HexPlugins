package hexchat.mute;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Trwałe przechowywanie wyciszeń w pliku YAML (domyślnie {@code mutes.yml} w folderze pluginu).
 * Cały plik jest przepisywany przy każdej zmianie — liczba wyciszeń na SMP jest niewielka,
 * więc jest to w pełni wystarczające i proste.
 */
public final class YamlMuteStorage implements MuteStorage {

    private static final String ROOT = "mutes";

    private final File file;
    private final Logger logger;
    private final Map<UUID, MuteEntry> cache = new HashMap<>();

    public YamlMuteStorage(File file, Logger logger) {
        this.file = Objects.requireNonNull(file, "file");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public synchronized Map<UUID, MuteEntry> loadAll() {
        cache.clear();
        if (!file.exists()) {
            return new HashMap<>();
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection(ROOT);
        if (root == null) {
            return new HashMap<>();
        }

        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                MuteEntry entry = new MuteEntry(
                        id,
                        section.getString("name", "?"),
                        section.getLong("until", 0L),
                        section.getString("reason", ""),
                        section.getLong("created", 0L)
                );
                cache.put(id, entry);
            } catch (IllegalArgumentException ex) {
                logger.warning("Pomijam niepoprawny wpis wyciszenia '" + key + "' w " + file.getName() + ".");
            }
        }

        return new HashMap<>(cache);
    }

    @Override
    public synchronized void save(MuteEntry entry) {
        cache.put(entry.playerId(), entry);
        flush();
    }

    @Override
    public synchronized void remove(UUID playerId) {
        if (cache.remove(playerId) != null) {
            flush();
        }
    }

    private void flush() {
        YamlConfiguration config = new YamlConfiguration();
        for (MuteEntry entry : cache.values()) {
            String base = ROOT + "." + entry.playerId();
            config.set(base + ".name", entry.playerName());
            config.set(base + ".until", entry.untilEpochMillis());
            config.set(base + ".reason", entry.reason());
            config.set(base + ".created", entry.createdAtEpochMillis());
        }

        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            config.save(file);
        } catch (IOException ex) {
            logger.warning("Nie udało się zapisać pliku wyciszeń '" + file.getName() + "': " + ex.getMessage());
        }
    }
}
