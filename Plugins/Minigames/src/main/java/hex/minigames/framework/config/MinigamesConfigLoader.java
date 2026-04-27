package hex.minigames.framework.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MinigamesConfigLoader {

    public MinigamesConfig load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "minigames.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);

        Map<String, GameTypeConfig> out = new HashMap<>();

        for (String gameTypeId : yml.getKeys(false)) {
            ConfigurationSection sec = yml.getConfigurationSection(gameTypeId);
            if (sec == null) {
                continue;
            }

            int minPlayers = sec.getInt("minPlayers", 2);
            int maxPlayers = sec.getInt("maxPlayers", 12);
            int countdownSeconds = sec.getInt("countdownSeconds", 20);
            int maxInstances = sec.getInt("maxInstances", 4);
            List<String> maps = sec.getStringList("maps");
            String spawnRaw = sec.getString("lobbySpawn", "world,0.5,80,0.5,0,0");

            if (maps.isEmpty()) {
                throw new IllegalArgumentException("No maps configured for gameType: " + gameTypeId);
            }

            GameTypeConfig cfg = new GameTypeConfig(
                    minPlayers,
                    maxPlayers,
                    countdownSeconds,
                    maxInstances,
                    List.copyOf(maps),
                    LobbySpawn.parse(spawnRaw)
            );

            out.put(gameTypeId, cfg);
        }

        return new MinigamesConfig(out);
    }
}

