package hexmobplaceholder.storage;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

public final class MobBaselineStorage {

    private final File file;
    private FileConfiguration data;

    public MobBaselineStorage(File file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public boolean load(Logger logger) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.severe("Could not create data folder: " + parent.getAbsolutePath());
                return false;
            }
            if (!file.exists() && !file.createNewFile()) {
                logger.severe("Could not create data file: " + file.getAbsolutePath());
                return false;
            }
            this.data = YamlConfiguration.loadConfiguration(file);
            return true;
        } catch (IOException ex) {
            logger.severe("Could not load data.yml: " + ex.getMessage());
            return false;
        }
    }

    public boolean save(Logger logger) {
        if (data == null) {
            return true;
        }
        try {
            data.save(file);
            return true;
        } catch (IOException ex) {
            logger.severe("Could not save data.yml: " + ex.getMessage());
            return false;
        }
    }

    public int baseline(UUID playerId, EntityType type) {
        ensureLoaded();
        return data.getInt(path(playerId, type), 0);
    }

    public void setBaseline(UUID playerId, EntityType type, int value) {
        ensureLoaded();
        data.set(path(playerId, type), Math.max(0, value));
    }

    private String path(UUID playerId, EntityType type) {
        return "players." + playerId + "." + type.name();
    }

    private void ensureLoaded() {
        if (data == null) {
            throw new IllegalStateException("MobBaselineStorage is not loaded");
        }
    }
}
