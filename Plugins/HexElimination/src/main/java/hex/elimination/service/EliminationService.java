package hex.elimination.service;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class EliminationService {

    private final JavaPlugin plugin;
    private final Set<UUID> eliminated = new HashSet<>();

    private final File storageFile;
    private YamlConfiguration storage;

    public EliminationService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "eliminated.yml");
        load();
    }

    public boolean isEliminated(UUID uuid) {
        return eliminated.contains(uuid);
    }

    public void eliminate(Player player) {
        eliminated.add(player.getUniqueId());
        save();
    }

    public boolean resurrect(OfflinePlayer target) {
        if (target == null || target.getUniqueId() == null) {
            return false;
        }

        boolean removed = eliminated.remove(target.getUniqueId());
        if (!removed) {
            return false;
        }

        save();

        Player online = target.getPlayer();
        if (online != null && online.isOnline()) {
            online.setGameMode(getResurrectGamemode());
        }

        return true;
    }

    public void applyRespawnRule(Player player) {
        if (!isEliminated(player.getUniqueId())) {
            return;
        }
        player.setGameMode(getEliminatedRespawnGamemode());
    }

    public GameMode getEliminatedRespawnGamemode() {
        String configured = plugin.getConfig().getString("settings.respawn-gamemode-for-eliminated", "SPECTATOR");
        try {
            return GameMode.valueOf(configured.toUpperCase());
        } catch (Exception ignored) {
            return GameMode.SPECTATOR;
        }
    }

    public GameMode getResurrectGamemode() {
        String configured = plugin.getConfig().getString("settings.resurrect-gamemode", "SURVIVAL");
        try {
            return GameMode.valueOf(configured.toUpperCase());
        } catch (Exception ignored) {
            return GameMode.SURVIVAL;
        }
    }

    public OfflinePlayer findPlayerByName(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }

        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(name)) {
                return offline;
            }
        }

        return null;
    }

    public void shutdown() {
        save();
    }

    private void load() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        if (!storageFile.exists()) {
            try {
                storageFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Nie mozna utworzyc eliminated.yml: " + e.getMessage());
            }
        }

        this.storage = YamlConfiguration.loadConfiguration(storageFile);
        eliminated.clear();

        for (String raw : storage.getStringList("eliminated")) {
            try {
                eliminated.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // skip invalid UUID entry
            }
        }
    }

    private void save() {
        if (storage == null) {
            storage = new YamlConfiguration();
        }

        storage.set("eliminated", eliminated.stream().map(UUID::toString).toList());
        try {
            storage.save(storageFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie mozna zapisac eliminated.yml: " + e.getMessage());
        }
    }
}

