package hex.elimination.service;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class EliminationService {

    private final JavaPlugin plugin;
    private final Set<UUID> eliminated = new HashSet<>();
    private final Map<UUID, Location> deathLocations = new HashMap<>();
    private boolean active;

    private final File storageFile;
    private YamlConfiguration storage;

    public EliminationService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.active = plugin.getConfig().getBoolean("settings.active-on-startup", true);
        this.storageFile = new File(plugin.getDataFolder(), "eliminated.yml");
        load();
    }

    public boolean isActive() {
        return active;
    }

    public void enable() {
        this.active = true;
    }

    public void disable() {
        this.active = false;
    }

    public boolean isEliminated(UUID uuid) {
        return eliminated.contains(uuid);
    }

    public boolean shouldProcessDeath(Player player) {
        boolean includeOps = plugin.getConfig().getBoolean("settings.include-ops-in-elimination", false);
        return includeOps || !player.isOp();
    }

    public void eliminate(Player player) {
        if (!active) {
            return;
        }
        UUID uuid = player.getUniqueId();
        eliminated.add(uuid);
        deathLocations.put(uuid, player.getLocation().clone());
        save();
    }

    public Set<UUID> getEliminatedUuids() {
        return new HashSet<>(eliminated);
    }

    /**
     * Wskrzesza wszystkich wyeliminowanych graczy, ustawiając im podany tryb gry
     * (online) i czyści listę eliminacji.
     *
     * @return liczba wyeliminowanych graczy, których dotyczyła operacja
     */
    public int resurrectAll(GameMode gameMode) {
        int count = eliminated.size();
        for (UUID uuid : eliminated) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.isOnline()) {
                online.setGameMode(gameMode);
            }
        }
        eliminated.clear();
        deathLocations.clear();
        save();
        return count;
    }

    public boolean resurrect(OfflinePlayer target) {
        if (target == null) {
            return false;
        }

        UUID uuid = target.getUniqueId();
        boolean removed = eliminated.remove(uuid);
        if (!removed) {
            return false;
        }

        deathLocations.remove(uuid);
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

    public Location getDeathLocation(UUID uuid) {
        Location location = deathLocations.get(uuid);
        return location == null ? null : location.clone();
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

    public void reloadConfig() {
        load();
    }

    public void shutdown() {
        save();
    }

    private void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Nie mozna utworzyc katalogu danych pluginu: " + plugin.getDataFolder());
        }

        if (!storageFile.exists()) {
            try {
                if (!storageFile.createNewFile()) {
                    plugin.getLogger().warning("Nie mozna utworzyc eliminated.yml.");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Nie mozna utworzyc eliminated.yml: " + e.getMessage());
            }
        }

        this.storage = YamlConfiguration.loadConfiguration(storageFile);
        eliminated.clear();
        deathLocations.clear();

        for (String raw : storage.getStringList("eliminated")) {
            try {
                eliminated.add(UUID.fromString(raw));
            } catch (IllegalArgumentException ignored) {
                // skip invalid UUID entry
            }
        }

        ConfigurationSection section = storage.getConfigurationSection("death-locations");
        if (section != null) {
            for (String raw : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(raw);
                    Location location = readLocation(section.getConfigurationSection(raw));
                    if (location != null) {
                        deathLocations.put(uuid, location);
                    }
                } catch (IllegalArgumentException ignored) {
                    // skip invalid UUID entry
                }
            }
        }
    }

    private void save() {
        if (storage == null) {
            storage = new YamlConfiguration();
        }

        storage.set("eliminated", eliminated.stream().map(UUID::toString).toList());
        storage.set("death-locations", null);
        for (Map.Entry<UUID, Location> entry : deathLocations.entrySet()) {
            writeLocation("death-locations." + entry.getKey(), entry.getValue());
        }
        try {
            storage.save(storageFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie mozna zapisac eliminated.yml: " + e.getMessage());
        }
    }

    private Location readLocation(ConfigurationSection section) {
        if (section == null) {
            return null;
        }

        String worldName = section.getString("world");
        if (worldName == null || worldName.isBlank()) {
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        return new Location(
                world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw"),
                (float) section.getDouble("pitch")
        );
    }

    private void writeLocation(String path, Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }

        storage.set(path + ".world", location.getWorld().getName());
        storage.set(path + ".x", location.getX());
        storage.set(path + ".y", location.getY());
        storage.set(path + ".z", location.getZ());
        storage.set(path + ".yaw", location.getYaw());
        storage.set(path + ".pitch", location.getPitch());
    }
}
