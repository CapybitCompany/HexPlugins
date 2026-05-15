package hexabovename.repository;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class YamlDisplayTextRepository implements DisplayTextRepository {

    private final File file;
    private Map<String, String> textByLowerName = Map.of();

    public YamlDisplayTextRepository(File file) {
        this.file = file;
    }

    @Override
    public void initialize() {
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection users = config.getConfigurationSection("users");
        if (users == null) {
            textByLowerName = Map.of();
            return;
        }

        Map<String, String> loaded = new HashMap<>();
        for (String key : users.getKeys(false)) {
            String text = users.getString(key + ".text");
            if (text == null || text.isBlank()) {
                continue;
            }
            loaded.put(key.toLowerCase(Locale.ROOT), text);
        }
        textByLowerName = Map.copyOf(loaded);
    }

    @Override
    public Map<UUID, String> loadDisplayTexts(Collection<PlayerSnapshot> players) {
        if (players.isEmpty() || textByLowerName.isEmpty()) {
            return Map.of();
        }

        Map<UUID, String> result = new HashMap<>();
        for (PlayerSnapshot player : players) {
            String text = textByLowerName.get(player.name().toLowerCase(Locale.ROOT));
            if (text != null && !text.isBlank()) {
                result.put(player.uuid(), text);
            }
        }
        return result;
    }
}
