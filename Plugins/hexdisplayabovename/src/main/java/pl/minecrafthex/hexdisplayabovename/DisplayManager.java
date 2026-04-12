package pl.minecrafthex.hexdisplayabovename;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DisplayManager {

    private final HexDisplayAboveNamePlugin plugin;
    private final Map<UUID, TextDisplay> displays = new ConcurrentHashMap<>();
    private BukkitTask task;

    public DisplayManager(HexDisplayAboveNamePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();

        long interval = Math.max(1L, plugin.getConfig().getLong("update-interval-ticks", 2L));

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, interval);
    }

    public void restart() {
        removeAllDisplays();
        start();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void removeAllDisplays() {
        for (TextDisplay display : displays.values()) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
        displays.clear();
    }

    private void tick() {
        Set<UUID> online = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());

            String text = getConfiguredText(player.getName());

            if (text == null || text.isBlank() || !shouldBeVisible(player)) {
                removeDisplay(player.getUniqueId());
                continue;
            }

            updateOrCreateDisplay(player, color(text));
        }

        List<UUID> toRemove = new ArrayList<>();
        for (UUID uuid : displays.keySet()) {
            if (!online.contains(uuid)) {
                toRemove.add(uuid);
            }
        }

        for (UUID uuid : toRemove) {
            removeDisplay(uuid);
        }
    }

    private boolean shouldBeVisible(Player player) {
        if (player.isDead()) return false;
        if (!player.isOnline()) return false;

        boolean useWorldWhitelist = plugin.getConfig().getBoolean("use-world-whitelist", false);
        if (useWorldWhitelist) {
            List<String> worlds = plugin.getConfig().getStringList("world-whitelist");
            if (!worlds.contains(player.getWorld().getName())) {
                return false;
            }
        }

        return true;
    }

    private void updateOrCreateDisplay(Player player, String text) {
        TextDisplay display = displays.get(player.getUniqueId());

        if (display == null || !display.isValid() || display.isDead()) {
            display = spawnDisplay(player, text);
            if (display == null) return;
            displays.put(player.getUniqueId(), display);
        }

        Location target = getTargetLocation(player);
        if (!sameWorld(display.getWorld(), target.getWorld())) {
            display.remove();
            TextDisplay newDisplay = spawnDisplay(player, text);
            if (newDisplay != null) {
                displays.put(player.getUniqueId(), newDisplay);
            } else {
                displays.remove(player.getUniqueId());
            }
            return;
        }

        display.teleport(target);
        display.setText(text);

        boolean showToSelf = plugin.getConfig().getBoolean("show-to-self", false);
        player.showEntity(plugin, display);

        if (!showToSelf) {
            player.hideEntity(plugin, display);
        }

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }

            if (viewer.getWorld().equals(player.getWorld())) {
                viewer.showEntity(plugin, display);
            } else {
                viewer.hideEntity(plugin, display);
            }
        }
    }

    private TextDisplay spawnDisplay(Player player, String text) {
        Location location = getTargetLocation(player);

        return location.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.setText(text);
            entity.setSeeThrough(false);
            entity.setShadowed(false);
            entity.setPersistent(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setDefaultBackground(false);
            entity.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
        });
    }

    private Location getTargetLocation(Player player) {
        double yOffset = plugin.getConfig().getDouble("y-offset", 2.65);
        return player.getLocation().clone().add(0.0, yOffset, 0.0);
    }

    private void removeDisplay(UUID uuid) {
        TextDisplay display = displays.remove(uuid);
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    private String getConfiguredText(String playerName) {
        ConfigurationSection section = plugin.getUsersConfig().getConfigurationSection("users");
        if (section == null) return null;

        for (String key : section.getKeys(false)) {
            if (key.equalsIgnoreCase(playerName)) {
                return plugin.getUsersConfig().getString("users." + key + ".text");
            }
        }

        return null;
    }

    private boolean sameWorld(World a, World b) {
        if (a == null || b == null) return false;
        return a.getUID().equals(b.getUID());
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}