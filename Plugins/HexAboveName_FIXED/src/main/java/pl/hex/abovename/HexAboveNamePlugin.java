package pl.hex.abovename;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class HexAboveNamePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String ENTITY_TAG = "hex_above_name_display";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Map<UUID, TextDisplay> activeDisplays = new HashMap<>();
    private BukkitTask updateTask;

    private double heightOffset;
    private float scale;
    private int updateIntervalTicks;
    private int teleportDurationTicks;
    private boolean shadow;
    private boolean seeThrough;
    private byte backgroundOpacity;
    private byte textOpacity;
    private Display.Billboard billboard;
    private boolean respectCanSee;
    private boolean hideInvisiblePotion;
    private boolean hideSpectator;
    private boolean hideDead;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        if (getConfig().getBoolean("settings.cleanup-stale-displays-on-startup", true)) {
            cleanupStaleDisplays();
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("hexabovename")).setExecutor(this);
        Objects.requireNonNull(getCommand("hexabovename")).setTabCompleter(this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshDisplay(player);
        }

        startUpdateTask();
    }

    @Override
    public void onDisable() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        removeAllDisplays();
    }

    private void loadSettings() {
        reloadConfig();
        heightOffset = getConfig().getDouble("settings.height-offset", 2.55D);
        scale = (float) getConfig().getDouble("settings.scale", 1.0D);
        updateIntervalTicks = Math.max(1, getConfig().getInt("settings.update-interval-ticks", 2));
        teleportDurationTicks = Math.max(0, Math.min(59, getConfig().getInt("settings.teleport-duration-ticks", updateIntervalTicks)));
        shadow = getConfig().getBoolean("settings.shadow", true);
        seeThrough = getConfig().getBoolean("settings.see-through", false);
        backgroundOpacity = (byte) clamp(getConfig().getInt("settings.background-opacity", 0), 0, 255);
        textOpacity = (byte) clamp(getConfig().getInt("settings.text-opacity", 255), 0, 255);
        billboard = parseBillboard(getConfig().getString("settings.billboard", "CENTER"));

        respectCanSee = getConfig().getBoolean("visibility.respect-player-can-see", true);
        hideInvisiblePotion = getConfig().getBoolean("visibility.hide-when-invisible-potion", true);
        hideSpectator = getConfig().getBoolean("visibility.hide-when-spectator", true);
        hideDead = getConfig().getBoolean("visibility.hide-when-dead", true);
    }

    private void startUpdateTask() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        updateTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (hasTitle(player.getUniqueId())) {
                    TextDisplay display = activeDisplays.get(player.getUniqueId());
                    if (display == null || display.isDead() || !display.isValid()) {
                        refreshDisplay(player);
                        display = activeDisplays.get(player.getUniqueId());
                    }
                    if (display != null) {
                        updateDisplayPosition(player, display);
                        updateDisplayVisibility(player, display);
                    }
                } else {
                    removeDisplay(player.getUniqueId());
                }
            }
        }, updateIntervalTicks, updateIntervalTicks);
    }

    private void refreshAllDisplays() {
        removeAllDisplays();
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshDisplay(player);
        }
    }

    private void refreshDisplay(Player player) {
        UUID uuid = player.getUniqueId();
        String title = getTitle(uuid);
        if (title == null || title.isBlank()) {
            removeDisplay(uuid);
            return;
        }

        TextDisplay display = activeDisplays.get(uuid);
        if (display == null || display.isDead() || !display.isValid() || !Objects.equals(display.getWorld(), player.getWorld())) {
            removeDisplay(uuid);
            display = spawnDisplay(player, title);
            activeDisplays.put(uuid, display);
        } else {
            applyDisplaySettings(display, title);
        }

        updateDisplayPosition(player, display);
        updateDisplayVisibility(player, display);
    }

    private TextDisplay spawnDisplay(Player player, String title) {
        Location location = titleLocation(player);
        TextDisplay display = player.getWorld().spawn(location, TextDisplay.class, entity -> {
            entity.addScoreboardTag(ENTITY_TAG);
            entity.setPersistent(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setRemoveWhenFarAway(false);
            applyDisplaySettings(entity, title);
        });
        return display;
    }

    private void applyDisplaySettings(TextDisplay display, String title) {
        display.text(color(title));
        display.setShadowed(shadow);
        display.setSeeThrough(seeThrough);
        display.setBackgroundColor(org.bukkit.Color.fromARGB(Byte.toUnsignedInt(backgroundOpacity), 0, 0, 0));
        display.setTextOpacity(textOpacity);
        display.setBillboard(billboard);
        display.setTeleportDuration(teleportDurationTicks);
        display.setTransformation(new Transformation(
                new Vector3f(0, 0, 0),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f(0, 0, 0, 1)
        ));
    }

    private void updateDisplayPosition(Player player, TextDisplay display) {
        if (!Objects.equals(display.getWorld(), player.getWorld())) {
            refreshDisplay(player);
            return;
        }
        display.teleport(titleLocation(player));
    }

    private Location titleLocation(Player player) {
        Location loc = player.getLocation().clone();
        loc.setY(loc.getY() + heightOffset);
        return loc;
    }

    private void updateDisplayVisibility(Player target, TextDisplay display) {
        boolean targetShouldHide = shouldHideBecauseOfState(target);

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boolean visible = !targetShouldHide;

            if (visible && respectCanSee && !viewer.equals(target) && !viewer.canSee(target)) {
                visible = false;
            }

            if (visible && !Objects.equals(viewer.getWorld(), target.getWorld())) {
                visible = false;
            }

            if (visible) {
                viewer.showEntity(this, display);
            } else {
                viewer.hideEntity(this, display);
            }
        }
    }

    private boolean shouldHideBecauseOfState(Player player) {
        if (!player.isOnline()) return true;
        if (hideDead && player.isDead()) return true;
        if (hideSpectator && player.getGameMode() == GameMode.SPECTATOR) return true;
        if (hideInvisiblePotion && player.hasPotionEffect(PotionEffectType.INVISIBILITY)) return true;
        return false;
    }

    private void removeDisplay(UUID uuid) {
        TextDisplay display = activeDisplays.remove(uuid);
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    private void removeAllDisplays() {
        for (TextDisplay display : new ArrayList<>(activeDisplays.values())) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
        activeDisplays.clear();
    }

    private void cleanupStaleDisplays() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(ENTITY_TAG)) {
                    entity.remove();
                }
            }
        }
    }

    private boolean hasTitle(UUID uuid) {
        return getConfig().isString("titles." + uuid + ".title");
    }

    private String getTitle(UUID uuid) {
        return getConfig().getString("titles." + uuid + ".title");
    }

    private void setTitle(Player player, String title) {
        UUID uuid = player.getUniqueId();
        getConfig().set("titles." + uuid + ".name", player.getName());
        getConfig().set("titles." + uuid + ".title", title);
        saveConfig();
        refreshDisplay(player);
    }

    private boolean clearTitleByName(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            getConfig().set("titles." + online.getUniqueId(), null);
            saveConfig();
            removeDisplay(online.getUniqueId());
            return true;
        }

        ConfigurationSection section = getConfig().getConfigurationSection("titles");
        if (section == null) return false;

        for (String uuidString : section.getKeys(false)) {
            String savedName = getConfig().getString("titles." + uuidString + ".name");
            if (savedName != null && savedName.equalsIgnoreCase(name)) {
                getConfig().set("titles." + uuidString, null);
                saveConfig();
                try {
                    removeDisplay(UUID.fromString(uuidString));
                } catch (IllegalArgumentException ignored) {
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hexabovename.admin")) {
            send(sender, getMessage("no-permission"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            startUpdateTask();
            refreshAllDisplays();
            send(sender, getMessage("reloaded"));
            return true;
        }

        if (args.length < 2) {
            send(sender, getMessage("usage"));
            return true;
        }

        String playerName = args[0];
        String action = args[1].toLowerCase(Locale.ROOT);

        if (action.equals("set")) {
            if (args.length < 3) {
                send(sender, getMessage("usage"));
                return true;
            }

            Player target = Bukkit.getPlayerExact(playerName);
            if (target == null) {
                send(sender, getMessage("player-not-found"));
                return true;
            }

            String title = joinArgs(args, 2);
            setTitle(target, title);
            send(sender, getMessage("title-set")
                    .replace("%player%", target.getName())
                    .replace("%title%", title));
            return true;
        }

        if (action.equals("clear")) {
            boolean cleared = clearTitleByName(playerName);
            if (!cleared) {
                send(sender, getMessage("player-not-found"));
                return true;
            }
            send(sender, getMessage("title-cleared-offline").replace("%player%", playerName));
            return true;
        }

        send(sender, getMessage("usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("hexabovename.admin")) return Collections.emptyList();

        if (args.length == 1) {
            List<String> list = new ArrayList<>();
            list.add("reload");
            for (Player player : Bukkit.getOnlinePlayers()) {
                list.add(player.getName());
            }
            return filter(list, args[0]);
        }

        if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            return filter(List.of("set", "clear"), args[1]);
        }

        if (args.length == 3 && args[1].equalsIgnoreCase("set")) {
            return List.of("&6&lMISTRZ", "&c&lELIMINOWANY", "&b&lVIP");
        }

        return Collections.emptyList();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> refreshDisplay(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeDisplay(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        removeDisplay(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> refreshDisplay(player), 2L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> refreshDisplay(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> refreshDisplay(player), 1L);
    }

    private String getMessage(String key) {
        return getConfig().getString("messages." + key, "&cMissing message: " + key);
    }

    private void send(CommandSender sender, String message) {
        String prefix = getConfig().getString("messages.prefix", "");
        sender.sendMessage(color(prefix + message));
    }

    private Component color(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }

    private String joinArgs(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) builder.append(' ');
            builder.append(args[i]);
        }
        return builder.toString();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }

    private Display.Billboard parseBillboard(String raw) {
        if (raw == null) return Display.Billboard.CENTER;
        try {
            return Display.Billboard.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Display.Billboard.CENTER;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
