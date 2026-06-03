package pl.hex.abovename;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class HexAboveNamePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String TAG = "hex_above_name";

    private final Map<UUID, TextDisplay> activeDisplays = new HashMap<>();
    private final Map<UUID, String> titles = new HashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacyAmpersand = LegacyComponentSerializer.legacyAmpersand();

    private NamespacedKey ownerKey;
    private BukkitTask visibilityTask;

    private double heightOffset;
    private float scale;
    private boolean shadow;
    private boolean seeThrough;
    private byte backgroundOpacity;
    private boolean defaultVisible;
    private Display.Billboard billboard;
    private int visibilityRefreshTicks;

    private boolean hideWhenVanished;
    private boolean hideWhenInvisiblePotion;
    private boolean hideWhenSpectator;
    private boolean respectPlayerCanSee;

    @Override
    public void onEnable() {
        this.ownerKey = new NamespacedKey(this, "owner");
        saveDefaultConfig();
        loadSettingsAndTitles();

        if (getConfig().getBoolean("settings.cleanup-old-displays-on-start", true)) {
            cleanupOldDisplays();
        }

        Objects.requireNonNull(getCommand("hexabovename")).setExecutor(this);
        Objects.requireNonNull(getCommand("hexabovename")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            applyIfConfigured(player);
        }
        startVisibilityTask();
    }

    @Override
    public void onDisable() {
        if (visibilityTask != null) visibilityTask.cancel();
        removeAllDisplays();
    }

    private void loadSettingsAndTitles() {
        reloadConfig();

        this.heightOffset = getConfig().getDouble("settings.height-offset", 0.65D);
        this.scale = (float) getConfig().getDouble("settings.scale", 1.0D);
        this.shadow = getConfig().getBoolean("settings.shadow", true);
        this.seeThrough = getConfig().getBoolean("settings.see-through", false);
        int opacity = Math.max(0, Math.min(255, getConfig().getInt("settings.background-opacity", 0)));
        this.backgroundOpacity = (byte) opacity;
        this.defaultVisible = getConfig().getBoolean("settings.default-visible", true);
        this.visibilityRefreshTicks = Math.max(5, getConfig().getInt("settings.visibility-refresh-ticks", 20));
        this.billboard = parseBillboard(getConfig().getString("settings.billboard", "CENTER"));

        this.hideWhenVanished = getConfig().getBoolean("visibility.hide-when-vanished", true);
        this.hideWhenInvisiblePotion = getConfig().getBoolean("visibility.hide-when-invisible-potion", true);
        this.hideWhenSpectator = getConfig().getBoolean("visibility.hide-when-spectator", true);
        this.respectPlayerCanSee = getConfig().getBoolean("visibility.respect-player-can-see", true);

        titles.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("titles");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String title = section.getString(key + ".title", "");
                    if (!title.isBlank()) {
                        titles.put(uuid, title);
                    }
                } catch (IllegalArgumentException ignored) {
                    getLogger().warning("Pominięto niepoprawny UUID w configu titles: " + key);
                }
            }
        }
    }

    private Display.Billboard parseBillboard(String value) {
        try {
            return Display.Billboard.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return Display.Billboard.CENTER;
        }
    }

    private void startVisibilityTask() {
        if (visibilityTask != null) visibilityTask.cancel();
        visibilityTask = Bukkit.getScheduler().runTaskTimer(this, this::updateAllVisibility, 20L, visibilityRefreshTicks);
    }

    private void reloadRuntime() {
        loadSettingsAndTitles();
        removeAllDisplays();
        cleanupOldDisplays();
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyIfConfigured(player);
        }
        startVisibilityTask();
    }

    private void applyIfConfigured(Player player) {
        String title = titles.get(player.getUniqueId());
        if (title == null || title.isBlank()) return;
        spawnOrUpdateDisplay(player, title);
    }

    private void spawnOrUpdateDisplay(Player player, String rawTitle) {
        removeDisplay(player.getUniqueId());

        Location location = player.getLocation();
        TextDisplay display = (TextDisplay) player.getWorld().spawnEntity(location, EntityType.TEXT_DISPLAY);
        display.addScoreboardTag(TAG);
        display.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());

        display.text(format(rawTitle));
        display.setBillboard(billboard);
        display.setShadowed(shadow);
        display.setSeeThrough(seeThrough);
        display.setDefaultBackground(false);
        display.setBackgroundColor(org.bukkit.Color.fromARGB(backgroundOpacity & 0xFF, 0, 0, 0));
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);
        display.setTeleportDuration(0);
        display.setViewRange(64.0F);
        display.setTransformation(new Transformation(
                new Vector3f(0.0F, (float) heightOffset, 0.0F),
                new AxisAngle4f(0.0F, 0.0F, 0.0F, 1.0F),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f(0.0F, 0.0F, 0.0F, 1.0F)
        ));

        if (!defaultVisible) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                viewer.hideEntity(this, display);
            }
        }

        player.addPassenger(display);
        activeDisplays.put(player.getUniqueId(), display);
        updateVisibilityForOwner(player);
    }

    private Component format(String raw) {
        if (raw.contains("<") && raw.contains(">")) {
            try {
                return miniMessage.deserialize(raw);
            } catch (Exception ignored) {
                return legacyAmpersand.deserialize(raw);
            }
        }
        return legacyAmpersand.deserialize(raw);
    }

    private void removeDisplay(UUID uuid) {
        TextDisplay display = activeDisplays.remove(uuid);
        if (display != null && !display.isDead()) {
            display.remove();
        }
    }

    private void removeAllDisplays() {
        for (TextDisplay display : new ArrayList<>(activeDisplays.values())) {
            if (display != null && !display.isDead()) {
                display.remove();
            }
        }
        activeDisplays.clear();
    }

    private void cleanupOldDisplays() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof TextDisplay && entity.getScoreboardTags().contains(TAG)) {
                    entity.remove();
                }
            }
        }
    }

    private void updateAllVisibility() {
        for (UUID ownerId : new ArrayList<>(activeDisplays.keySet())) {
            Player owner = Bukkit.getPlayer(ownerId);
            TextDisplay display = activeDisplays.get(ownerId);
            if (owner == null || !owner.isOnline() || display == null || display.isDead()) {
                removeDisplay(ownerId);
                continue;
            }
            updateVisibilityForOwner(owner);
        }
    }

    private void updateVisibilityForOwner(Player owner) {
        TextDisplay display = activeDisplays.get(owner.getUniqueId());
        if (display == null || display.isDead()) return;

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boolean visible = shouldViewerSeeTitle(viewer, owner);
            if (visible) {
                viewer.showEntity(this, display);
            } else {
                viewer.hideEntity(this, display);
            }
        }
    }

    private boolean shouldViewerSeeTitle(Player viewer, Player owner) {
        if (!viewer.getWorld().equals(owner.getWorld())) return false;
        if (hideWhenSpectator && owner.getGameMode() == GameMode.SPECTATOR) return false;
        if (hideWhenInvisiblePotion && owner.hasPotionEffect(PotionEffectType.INVISIBILITY)) return false;
        if (respectPlayerCanSee && !viewer.canSee(owner)) return false;
        if (hideWhenVanished && isVanishedByMetadata(owner)) return false;
        return true;
    }

    private boolean isVanishedByMetadata(Player player) {
        return player.hasMetadata("vanished")
                || player.hasMetadata("vanish")
                || player.hasMetadata("invisible")
                || player.hasMetadata("essentialsvanished");
    }

    private void saveTitle(UUID uuid, String name, String title) {
        String path = "titles." + uuid;
        getConfig().set(path + ".name", name);
        getConfig().set(path + ".title", title);
        saveConfig();
        titles.put(uuid, title);
    }

    private void clearTitle(UUID uuid) {
        getConfig().set("titles." + uuid, null);
        saveConfig();
        titles.remove(uuid);
        removeDisplay(uuid);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            applyIfConfigured(event.getPlayer());
            updateAllVisibility();
        }, 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeDisplay(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        respawnAfterMove(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (!event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            respawnAfterMove(event.getPlayer());
        } else {
            Bukkit.getScheduler().runTaskLater(this, () -> updateVisibilityForOwner(event.getPlayer()), 1L);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        respawnAfterMove(event.getPlayer());
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> updateVisibilityForOwner(event.getPlayer()), 1L);
    }

    @EventHandler
    public void onPotion(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getModifiedType() == PotionEffectType.INVISIBILITY) {
            Bukkit.getScheduler().runTaskLater(this, () -> updateVisibilityForOwner(player), 1L);
        }
    }

    private void respawnAfterMove(Player player) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            String title = titles.get(player.getUniqueId());
            if (title != null && player.isOnline()) {
                spawnOrUpdateDisplay(player, title);
            }
        }, 2L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hexabovename.admin")) {
            send(sender, getConfig().getString("messages.no-permission"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadRuntime();
            send(sender, getConfig().getString("messages.reloaded"));
            return true;
        }

        if (args.length < 2) {
            send(sender, getConfig().getString("messages.usage"));
            return true;
        }

        String nick = args[0];
        String action = args[1].toLowerCase(Locale.ROOT);
        OfflinePlayer offline = resolvePlayer(nick);
        if (offline == null || (!offline.hasPlayedBefore() && !offline.isOnline())) {
            send(sender, getConfig().getString("messages.player-not-found"));
            return true;
        }

        UUID uuid = offline.getUniqueId();
        String name = offline.getName() == null ? nick : offline.getName();

        if (action.equals("clear")) {
            clearTitle(uuid);
            send(sender, getConfig().getString("messages.cleared")
                    .replace("<player>", name));
            return true;
        }

        if (action.equals("set")) {
            if (args.length < 3) {
                send(sender, getConfig().getString("messages.usage"));
                return true;
            }
            String title = String.join(" ", List.of(args).subList(2, args.length));
            saveTitle(uuid, name, title);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                spawnOrUpdateDisplay(online, title);
            }
            send(sender, getConfig().getString("messages.set")
                    .replace("<player>", name)
                    .replace("<title>", title));
            return true;
        }

        send(sender, getConfig().getString("messages.usage"));
        return true;
    }

    private OfflinePlayer resolvePlayer(String nick) {
        Player online = Bukkit.getPlayerExact(nick);
        if (online != null) return online;
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(nick)) {
                return offline;
            }
        }
        return null;
    }

    private void send(CommandSender sender, String message) {
        String prefix = getConfig().getString("messages.prefix", "");
        sender.sendMessage(format(prefix + (message == null ? "" : message)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("hexabovename.admin")) return List.of();
        if (args.length == 1) {
            List<String> values = new ArrayList<>();
            values.add("reload");
            for (Player player : Bukkit.getOnlinePlayers()) values.add(player.getName());
            return startsWith(values, args[0]);
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            return startsWith(List.of("set", "clear"), args[1]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("set")) {
            return List.of("<gold><bold>TYTUŁ</bold></gold>", "&6&lTYTUŁ");
        }
        return List.of();
    }

    private List<String> startsWith(List<String> source, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : source) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(value);
        }
        return out;
    }
}
