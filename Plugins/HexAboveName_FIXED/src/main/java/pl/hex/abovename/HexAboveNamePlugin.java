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
import pl.hex.abovename.cache.TitleCache;
import pl.hex.abovename.storage.MySqlConfig;
import pl.hex.abovename.storage.MySqlTitleStorage;
import pl.hex.abovename.storage.StoredTitle;
import pl.hex.abovename.storage.TitleStorage;
import pl.hex.abovename.storage.YamlTitleStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public final class HexAboveNamePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final String ENTITY_TAG = "hex_above_name_display";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Map<UUID, TextDisplay> activeDisplays = new HashMap<>();
    private BukkitTask updateTask;

    // Storage + cache wiring. The repeating update task reads only from the
    // cache; storage is touched only on startup/reload/set/clear.
    private final TitleCache titleCache = new TitleCache();
    private TitleStorage titleStorage;
    private boolean usingDb;

    // Incremented on every init/reload AND on disable. Async callbacks compare
    // their captured generation against this value before mutating shared
    // state (titleStorage, titleCache, usingDb, displays, plugin enable bit).
    // A mismatch means the callback is stale and must close its candidate
    // storage and abort without side effects.
    private final AtomicLong storageGeneration = new AtomicLong();

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

        if (!initStorageAndLoadCache()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        startUpdateTask();
    }

    @Override
    public void onDisable() {
        // Bump the generation BEFORE closing storage so any in-flight async
        // callbacks observe a mismatch and skip mutation.
        storageGeneration.incrementAndGet();
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        removeAllDisplays();
        TitleStorage current = titleStorage;
        titleStorage = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }
        titleCache.clear();
    }

    /**
     * Initializes a fresh storage candidate from config.yml, runs the schema
     * bootstrap async, and on a matching generation swaps it into place and
     * closes the previous storage. Never mutates shared state from an async
     * thread — every observable change happens inside a runTask on the
     * Bukkit main thread, after a generation check.
     *
     * Returns false ONLY when synchronous validation fails (e.g. table name
     * malformed) AND fallback is disabled — i.e. the caller (onEnable) should
     * disable the plugin and reload should reject. On synchronous return the
     * previous storage is still active and the newly created candidate (if any)
     * has been closed.
     *
     * Never blocks the Bukkit main thread — no .get()/.join() on futures.
     */
    private boolean initStorageAndLoadCache() {
        final long gen = storageGeneration.incrementAndGet();

        String typeRaw = getConfig().getString("storage.type", "YAML");
        final boolean fallback = getConfig().getBoolean("storage.fallback-to-yaml-if-db-unavailable", true);
        final boolean migrate = getConfig().getBoolean("storage.migrate-yaml-to-db-on-startup", false);
        final boolean wantDb = "MYSQL".equalsIgnoreCase(typeRaw);

        // Build candidate (does not block on a connection thanks to
        // setInitializationFailTimeout(-1) inside MySqlTitleStorage).
        TitleStorage candidateTmp = null;
        boolean candidateIsDb = false;
        if (wantDb) {
            try {
                MySqlConfig mysqlConfig = readMySqlConfig();
                candidateTmp = new MySqlTitleStorage(mysqlConfig, getLogger());
                candidateIsDb = true;
            } catch (Throwable t) {
                getLogger().warning("HexAboveName: MySQL storage init failed: " + t.getMessage());
                if (!fallback) {
                    getLogger().severe("storage.fallback-to-yaml-if-db-unavailable=false. Not rebuilding storage.");
                    return false;
                }
                getLogger().warning("Falling back to YAML storage.");
            }
        }
        if (candidateTmp == null) {
            candidateTmp = new YamlTitleStorage(this);
            candidateIsDb = false;
        }

        final TitleStorage candidate = candidateTmp;
        final boolean candidateIsDbFinal = candidateIsDb;

        candidate.ensureSchema().whenComplete((unused, err) -> {
            if (gen != storageGeneration.get()) {
                // A newer reload/disable already happened — discard candidate.
                closeQuietly(candidate);
                return;
            }
            if (err != null) {
                getLogger().warning("HexAboveName: schema bootstrap failed: " + err.getMessage());
                if (candidateIsDbFinal && !fallback) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (gen != storageGeneration.get()) {
                            closeQuietly(candidate);
                            return;
                        }
                        getLogger().severe("MySQL unavailable and fallback disabled. Disabling plugin.");
                        closeQuietly(candidate);
                        getServer().getPluginManager().disablePlugin(this);
                    });
                    return;
                }
                if (candidateIsDbFinal) {
                    // MySQL bootstrap failed but fallback is enabled: try YAML.
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (gen != storageGeneration.get()) {
                            closeQuietly(candidate);
                            return;
                        }
                        getLogger().warning("Falling back to YAML after MySQL bootstrap failure.");
                        closeQuietly(candidate);
                        commitStorage(gen, new YamlTitleStorage(this), false);
                        kickoffLoadAll(gen);
                    });
                    return;
                }
                // YAML ensureSchema cannot really fail, but log + carry on.
            }
            // Schema OK -> commit and load.
            Bukkit.getScheduler().runTask(this, () -> {
                if (gen != storageGeneration.get()) {
                    closeQuietly(candidate);
                    return;
                }
                commitStorage(gen, candidate, candidateIsDbFinal);
                kickoffLoadAll(gen);
                if (candidateIsDbFinal && migrate) {
                    migrateYamlIntoDbAsync(gen);
                }
            });
        });
        return true;
    }

    /**
     * Main-thread swap. Closes the previous storage only after the new one is
     * installed, so any concurrent set/clear sees a continuous storage.
     */
    private void commitStorage(long expectedGen, TitleStorage newStorage, boolean isDb) {
        if (expectedGen != storageGeneration.get()) {
            closeQuietly(newStorage);
            return;
        }
        TitleStorage previous = titleStorage;
        titleStorage = newStorage;
        usingDb = isDb;
        if (previous != null && previous != newStorage) {
            closeQuietly(previous);
        }
    }

    private static void closeQuietly(TitleStorage storage) {
        if (storage == null) return;
        try {
            storage.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * Reads the storage.mysql.* section into an immutable {@link MySqlConfig}.
     */
    private MySqlConfig readMySqlConfig() {
        return new MySqlConfig(
                getConfig().getString("storage.mysql.host", "localhost"),
                getConfig().getInt("storage.mysql.port", 3306),
                getConfig().getString("storage.mysql.database", "minecraft"),
                getConfig().getString("storage.mysql.username", "root"),
                getConfig().getString("storage.mysql.password", ""),
                getConfig().getString("storage.mysql.table", "hex_above_name_titles"),
                getConfig().getBoolean("storage.mysql.use-ssl", false),
                getConfig().getInt("storage.mysql.pool.maximum-pool-size", 5),
                getConfig().getInt("storage.mysql.pool.minimum-idle", 1),
                getConfig().getLong("storage.mysql.pool.connection-timeout-ms", 10_000L),
                getConfig().getLong("storage.mysql.pool.max-lifetime-ms", 1_800_000L)
        );
    }

    /**
     * Async loadAll then main-thread cache replace + display refresh.
     * Honors storage.load-on-startup. Aborts silently if generation no
     * longer matches.
     */
    private void kickoffLoadAll(long expectedGen) {
        if (!getConfig().getBoolean("storage.load-on-startup", true)) {
            return;
        }
        TitleStorage storage = titleStorage;
        if (storage == null) {
            return;
        }
        storage.loadAll().whenComplete((map, err) -> {
            if (expectedGen != storageGeneration.get()) {
                return;
            }
            if (err != null) {
                getLogger().warning("Failed to load titles: " + err.getMessage());
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> {
                if (expectedGen != storageGeneration.get()) {
                    return;
                }
                titleCache.replaceAll(map);
                refreshAllDisplays();
            });
        });
    }

    /**
     * One-shot copy of every YAML title into the active DB storage. Does not
     * delete YAML entries. Safe to run multiple times because the DB upsert
     * is idempotent.
     */
    private void migrateYamlIntoDbAsync(long expectedGen) {
        if (!usingDb || titleStorage == null) {
            return;
        }
        final TitleStorage activeStorage = titleStorage;
        YamlTitleStorage yaml = new YamlTitleStorage(this);
        yaml.loadAll().thenAccept(map -> {
            if (expectedGen != storageGeneration.get()) {
                return;
            }
            if (map.isEmpty()) {
                return;
            }
            getLogger().info("Migrating " + map.size() + " YAML titles to MySQL...");
            int[] done = {0};
            for (StoredTitle stored : map.values()) {
                activeStorage.save(stored.uuid(), stored.name(), stored.title())
                        .whenComplete((v, ex) -> {
                            if (expectedGen != storageGeneration.get()) {
                                return;
                            }
                            if (ex != null) {
                                getLogger().warning("Migration failed for " + stored.uuid() + ": " + ex.getMessage());
                            }
                            if (++done[0] == map.size()) {
                                getLogger().info("YAML -> MySQL migration complete.");
                            }
                        });
            }
        });
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
        String title = titleCache.titleOf(uuid);
        return title != null && !title.isBlank();
    }

    private String getTitle(UUID uuid) {
        return titleCache.titleOf(uuid);
    }

    /**
     * Updates cache + display synchronously on the main thread, then persists
     * asynchronously. Failures are logged but do not block the server thread
     * nor revert the in-memory title.
     */
    private void setTitle(Player player, String title) {
        UUID uuid = player.getUniqueId();
        titleCache.put(new StoredTitle(uuid, player.getName(), title));
        refreshDisplay(player);
        persistAsync(() -> titleStorage.save(uuid, player.getName(), title),
                "save title for " + player.getName());
    }

    /**
     * Dispatches the /hexabovename clear flow.
     *
     * Fast path (sync): online player OR cache hit -> remove + display + async
     * persist + immediate ack.
     *
     * Slow path (async): cache miss -> send "lookup queued" message and call
     * titleStorage.findUuidByName(name) async. The completion handler hops back
     * to the Bukkit main thread, then EITHER removes + acks OR sends
     * player-not-found. Generation-checked so a reload in between aborts the
     * tail safely.
     */
    private void handleClearByName(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            applyClear(sender, online.getUniqueId(), name);
            return;
        }
        Optional<UUID> cached = titleCache.findUuidByName(name);
        if (cached.isPresent()) {
            applyClear(sender, cached.get(), name);
            return;
        }
        // Cache miss: dispatch async storage lookup.
        TitleStorage storage = titleStorage;
        if (storage == null) {
            send(sender, getMessage("player-not-found"));
            return;
        }
        send(sender, getMessage("title-clear-lookup").replace("%player%", name));
        final long expectedGen = storageGeneration.get();
        storage.findUuidByName(name).whenComplete((found, err) -> {
            if (err != null) {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (expectedGen != storageGeneration.get()) return;
                    getLogger().warning("HexAboveName: offline clear lookup failed for "
                            + name + ": " + err.getMessage());
                    send(sender, getMessage("player-not-found"));
                });
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> {
                if (expectedGen != storageGeneration.get()) return;
                if (found.isEmpty()) {
                    send(sender, getMessage("player-not-found"));
                    return;
                }
                applyClear(sender, found.get(), name);
            });
        });
    }

    /**
     * Main-thread clear: cache removal, display removal, async storage delete,
     * ack message. Used by both fast and slow paths of {@link #handleClearByName}.
     */
    private void applyClear(CommandSender sender, UUID uuid, String displayName) {
        titleCache.remove(uuid);
        removeDisplay(uuid);
        TitleStorage storage = titleStorage;
        if (storage != null) {
            persistAsync(() -> storage.delete(uuid),
                    "delete title for " + displayName);
        }
        send(sender, getMessage("title-cleared-offline").replace("%player%", displayName));
    }

    private void persistAsync(java.util.function.Supplier<CompletableFuture<Void>> work, String description) {
        try {
            work.get().exceptionally(ex -> {
                getLogger().warning("HexAboveName: failed to " + description + ": " + ex.getMessage());
                return null;
            });
        } catch (Exception ex) {
            getLogger().warning("HexAboveName: failed to " + description + ": " + ex.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hexabovename.admin")) {
            send(sender, getMessage("no-permission"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            // The previous storage stays active throughout init; commitStorage
            // swaps + closes it ONLY when the new schema bootstrap succeeds.
            // If init returns false (synchronous validation fail + no fallback)
            // the previous storage is untouched.
            if (!initStorageAndLoadCache()) {
                send(sender, "&cReload failed - check console (storage init).");
                return true;
            }
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
            handleClearByName(sender, playerName);
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
