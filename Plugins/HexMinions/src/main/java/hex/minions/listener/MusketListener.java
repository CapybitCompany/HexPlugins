package hex.minions.listener;

import hex.minions.service.MinionService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MusketListener implements Listener {
    private static final AxisAngle4f NO_ROTATION = new AxisAngle4f(0.0F, 0.0F, 1.0F, 0.0F);
    private final Plugin plugin;
    private final MinionService service;
    private final NamespacedKey musketUuidKey;
    private final NamespacedKey loadedKey;
    private final NamespacedKey shotsLeftKey;
    private final NamespacedKey bulletKey;
    private final Map<UUID, LoadingState> loading = new ConcurrentHashMap<>();
    private final Map<UUID, BulletState> bullets = new ConcurrentHashMap<>();
    private volatile Settings settings;

    public MusketListener(Plugin plugin, MinionService service) {
        this.plugin = plugin;
        this.service = service;
        this.musketUuidKey = new NamespacedKey(plugin, "musket_uuid");
        this.loadedKey = new NamespacedKey(plugin, "musket_loaded");
        this.shotsLeftKey = new NamespacedKey(plugin, "musket_shots_left");
        this.bulletKey = new NamespacedKey(plugin, "musket_bullet");
        reload();
    }

    public void reload() {
        this.settings = Settings.load(plugin);
    }

    public void shutdown() {
        for (LoadingState state : loading.values()) {
            state.task().cancel();
        }
        loading.clear();
        for (Map.Entry<UUID, BulletState> entry : bullets.entrySet()) {
            entry.getValue().flightTask().cancel();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity != null) entity.remove();
        }
        bullets.clear();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Settings cfg = settings;
        if (!cfg.enabled()) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        ItemStack item = event.getItem();
        if (!isMusket(item, cfg)) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (loading.containsKey(player.getUniqueId())) return;
        item = ensureMusketState(player, hand, item, cfg);
        if (item == null || item.getType().isAir()) return;

        int shotsLeft = shotsLeft(item, cfg);
        if (shotsLeft <= 0) {
            player.sendActionBar(Component.text("Muszkiet jest zużyty."));
            return;
        }
        if (isLoaded(item)) {
            shoot(player, hand, item, cfg);
            return;
        }
        if (!hasAmmo(player, cfg)) {
            player.sendActionBar(Component.text("Brak amunicji do muszkietu."));
            return;
        }
        startLoading(player, hand, item, cfg);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        cancelLoading(event.getPlayer(), true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        cancelLoading(event.getPlayer(), true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        cancelLoading(event.getPlayer(), true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            cancelLoading(player, true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelLoading(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBulletDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager().getPersistentDataContainer().has(bulletKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosion(ExplosionPrimeEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(bulletKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Entity projectile = event.getEntity();
        if (!projectile.getPersistentDataContainer().has(bulletKey, PersistentDataType.BYTE)) return;
        BulletState state = bullets.remove(projectile.getUniqueId());
        if (state != null) state.flightTask().cancel();
        projectile.remove();

        Entity hit = event.getHitEntity();
        if (!(hit instanceof LivingEntity target)) return;
        if (state == null || target.getUniqueId().equals(state.shooterId())) return;
        Player shooter = Bukkit.getPlayer(state.shooterId());
        if (target instanceof Player && !service.towns().isPvpAllowed(target.getLocation())) {
            return;
        }
        if (target instanceof Player playerTarget && isShieldBlocking(playerTarget, projectile.getVelocity())) {
            playerTarget.getWorld().playSound(playerTarget.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0F, 1.0F);
            return;
        }
        double distance = state.start().distance(target.getLocation());
        double damage = damageForDistance(distance, settings);
        applyDamage(target, shooter, damage, settings);
    }

    private void startLoading(Player player, EquipmentSlot hand, ItemStack item, Settings cfg) {
        UUID playerId = player.getUniqueId();
        UUID itemId = musketUuid(item);
        if (itemId == null) return;
        int totalTicks = Math.max(1, (int) Math.round(cfg.reloadSeconds() * 20.0D));
        int interval = Math.max(1, cfg.loadingBarIntervalTicks());
        player.getWorld().playSound(player.getLocation(), cfg.reloadStartSound(), cfg.reloadStartVolume(), cfg.reloadStartPitch());

        BukkitRunnable runnable = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                ItemStack current = itemInHand(player, hand);
                LoadingState state = loading.get(playerId);
                if (state == null || !state.itemId().equals(itemId) || !isSameMusket(current, itemId, cfg)) {
                    cancel();
                    loading.remove(playerId);
                    return;
                }
                elapsed += interval;
                double progress = Math.min(1.0D, elapsed / (double) totalTicks);
                updateLoadingBar(current, progress);
                replaceHand(player, hand, current);
                player.sendActionBar(Component.text("Ładowanie muszkietu " + Math.round(progress * 100.0D) + "%"));
                if (progress >= 1.0D) {
                    if (!consumeAmmo(player, cfg)) {
                        player.sendActionBar(Component.text("Brak amunicji do muszkietu."));
                        restoreDurabilityBar(current, cfg);
                        replaceHand(player, hand, current);
                    } else {
                        setLoaded(current, true);
                        restoreDurabilityBar(current, cfg);
                        replaceHand(player, hand, current);
                        player.getWorld().playSound(player.getLocation(), cfg.reloadFinishSound(), cfg.reloadFinishVolume(), cfg.reloadFinishPitch());
                        player.sendActionBar(Component.text("Muszkiet załadowany."));
                    }
                    loading.remove(playerId);
                    cancel();
                }
            }
        };
        BukkitTask task = runnable.runTaskTimer(plugin, 0L, interval);
        loading.put(playerId, new LoadingState(itemId, hand, task));
    }

    private void cancelLoading(Player player, boolean restoreBar) {
        LoadingState state = loading.remove(player.getUniqueId());
        if (state == null) return;
        state.task().cancel();
        if (restoreBar) {
            ItemStack item = itemInHand(player, state.hand());
            if (isSameMusket(item, state.itemId(), settings)) {
                restoreDurabilityBar(item, settings);
                replaceHand(player, state.hand(), item);
                player.sendActionBar(Component.text("Ładowanie przerwane."));
            }
        }
    }

    private void shoot(Player player, EquipmentSlot hand, ItemStack item, Settings cfg) {
        int shotsLeft = shotsLeft(item, cfg);
        if (shotsLeft <= 0) return;
        setLoaded(item, false);
        setShotsLeft(item, shotsLeft - 1);
        restoreDurabilityBar(item, cfg);
        if (hand == EquipmentSlot.OFF_HAND) player.swingOffHand();
        else player.swingMainHand();
        if (shotsLeft - 1 <= 0) {
            replaceHand(player, hand, new ItemStack(Material.AIR));
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 1.0F);
        } else {
            replaceHand(player, hand, item);
        }

        Vector direction = player.getEyeLocation().getDirection().normalize();
        Location origin = player.getLocation().add(0.0D, cfg.smokeYOffset(), 0.0D).add(direction.clone().multiply(cfg.smokeForwardOffset()));
        player.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, origin, cfg.smokeParticleCount(), 0.18D, 0.18D, 0.18D, 0.02D);
        player.getWorld().playSound(player.getLocation(), cfg.shotSound(), cfg.shotVolume(), cfg.shotPitch());

        Location spawn = origin.clone().add(direction.clone().multiply(0.15D));
        ItemDisplay projectile = player.getWorld().spawn(spawn, ItemDisplay.class, display -> {
            display.setItemStack(new ItemStack(Material.FIRE_CHARGE));
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setGravity(false);
            display.setSilent(true);
            display.setViewRange(64.0F);
            display.setTransformation(new Transformation(
                    new Vector3f(0.0F, 0.0F, 0.0F),
                    NO_ROTATION,
                    new Vector3f((float) cfg.projectileVisualScale(), (float) cfg.projectileVisualScale(), (float) cfg.projectileVisualScale()),
                    NO_ROTATION
            ));
            display.getPersistentDataContainer().set(bulletKey, PersistentDataType.BYTE, (byte) 1);
        });
        scheduleBullet(projectile, player, direction, spawn, cfg);
    }

    private void scheduleBullet(ItemDisplay projectile, Player shooter, Vector initialDirection, Location start, Settings cfg) {
        int lifetimeTicks = Math.max(1, (int) Math.round(cfg.projectileLifetimeSeconds() * 20.0D));
        Vector velocity = initialDirection.clone().normalize().multiply(cfg.projectileSpeedBlocksPerSecond() / 20.0D);
        UUID shooterId = shooter.getUniqueId();
        BukkitRunnable runnable = new BukkitRunnable() {
            private int lived;
            private Vector currentVelocity = velocity.clone();

            @Override
            public void run() {
                if (!projectile.isValid() || projectile.isDead()) {
                    bullets.remove(projectile.getUniqueId());
                    cancel();
                    return;
                }
                lived++;
                if (lived >= lifetimeTicks) {
                    bullets.remove(projectile.getUniqueId());
                    projectile.remove();
                    cancel();
                    return;
                }

                Location from = projectile.getLocation();
                Vector step = currentVelocity.clone();
                double stepDistance = Math.max(0.01D, step.length());
                Vector direction = step.clone().normalize();
                HitResult hit = traceBullet(from, direction, stepDistance, shooterId, cfg);
                if (hit != null) {
                    bullets.remove(projectile.getUniqueId());
                    if (hit.target() != null) handleBulletTarget(hit.target(), shooterId, start, currentVelocity);
                    projectile.remove();
                    cancel();
                    return;
                }

                currentVelocity = currentVelocity.clone();
                currentVelocity.setY(currentVelocity.getY() - cfg.projectileGravityPerTick());
                projectile.teleport(from.add(step));
            }
        };
        BukkitTask task = runnable.runTaskTimer(plugin, 1L, 1L);
        bullets.put(projectile.getUniqueId(), new BulletState(shooterId, start, task));
    }

    private HitResult traceBullet(Location from, Vector direction, double distance, UUID shooterId, Settings cfg) {
        World world = from.getWorld();
        if (world == null) return null;
        RayTraceResult blockHit = world.rayTraceBlocks(from, direction, distance, FluidCollisionMode.NEVER, true);
        RayTraceResult entityHit = world.rayTraceEntities(from, direction, distance, cfg.projectileHitboxRadius(), entity ->
                entity instanceof LivingEntity && !entity.getUniqueId().equals(shooterId));
        double blockDistance = hitDistance(from, blockHit);
        double entityDistance = hitDistance(from, entityHit);
        if (blockHit != null && blockDistance <= entityDistance) return new HitResult(null);
        if (entityHit != null && entityHit.getHitEntity() instanceof LivingEntity target) return new HitResult(target);
        return null;
    }

    private double hitDistance(Location from, RayTraceResult result) {
        if (result == null || result.getHitPosition() == null) return Double.MAX_VALUE;
        return result.getHitPosition().distance(from.toVector());
    }

    private void handleBulletTarget(LivingEntity target, UUID shooterId, Location start, Vector projectileVelocity) {
        if (target.getUniqueId().equals(shooterId)) return;
        Player shooter = Bukkit.getPlayer(shooterId);
        if (target instanceof Player && !service.towns().isPvpAllowed(target.getLocation())) {
            return;
        }
        if (target instanceof Player playerTarget && isShieldBlocking(playerTarget, projectileVelocity)) {
            playerTarget.getWorld().playSound(playerTarget.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0F, 1.0F);
            return;
        }
        double distance = start.distance(target.getLocation());
        double damage = damageForDistance(distance, settings);
        applyDamage(target, shooter, damage, settings);
    }

    private double damageForDistance(double distance, Settings cfg) {
        double damage = Math.max(0.0D, cfg.damageHearts() * 2.0D);
        if (distance <= cfg.fullDamageRange()) return damage;
        int steps = (int) Math.ceil((distance - cfg.fullDamageRange()) / Math.max(1.0D, cfg.falloffBlocks()));
        double multiplier = Math.pow(1.0D - cfg.falloffPercentPerStep() / 100.0D, Math.max(0, steps));
        return Math.max(0.0D, damage * multiplier);
    }

    private void applyDamage(LivingEntity target, Player shooter, double damage, Settings cfg) {
        if (damage <= 0.0D || target.isDead()) return;
        double ignored = damage * cfg.armorIgnoredFraction();
        double normal = damage - ignored;
        if (normal > 0.0D) {
            if (shooter == null) target.damage(normal);
            else target.damage(normal, shooter);
        }
        if (ignored > 0.0D && !target.isDead()) {
            target.setHealth(Math.max(0.0D, target.getHealth() - ignored));
        }
    }

    private boolean isShieldBlocking(Player player, Vector projectileVelocity) {
        if (!player.isBlocking()) return false;
        PlayerInventory inventory = player.getInventory();
        if (inventory.getItemInMainHand().getType() != Material.SHIELD && inventory.getItemInOffHand().getType() != Material.SHIELD) return false;
        Vector incoming = projectileVelocity.clone();
        if (incoming.lengthSquared() <= 0.0001D) return true;
        incoming.normalize().multiply(-1.0D);
        return player.getEyeLocation().getDirection().normalize().dot(incoming) > 0.25D;
    }

    private boolean hasAmmo(Player player, Settings cfg) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isAmmo(stack, cfg)) return true;
        }
        return false;
    }

    private boolean consumeAmmo(Player player, Settings cfg) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (!isAmmo(stack, cfg)) continue;
            if (stack.getAmount() <= 1) contents[i] = null;
            else stack.setAmount(stack.getAmount() - 1);
            inventory.setContents(contents);
            return true;
        }
        return false;
    }

    private boolean isMusket(ItemStack item, Settings cfg) {
        return item != null && !item.getType().isAir() && service.specialItems().readSpecialItemId(item).map(id -> id.equalsIgnoreCase(cfg.itemId())).orElse(false);
    }

    private boolean isAmmo(ItemStack item, Settings cfg) {
        return item != null && !item.getType().isAir() && service.specialItems().readSpecialItemId(item).map(id -> id.equalsIgnoreCase(cfg.ammoItemId())).orElse(false);
    }

    private ItemStack ensureMusketState(Player player, EquipmentSlot hand, ItemStack item, Settings cfg) {
        if (item == null || item.getType().isAir()) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        boolean changed = false;
        if (!pdc.has(musketUuidKey, PersistentDataType.STRING)) {
            pdc.set(musketUuidKey, PersistentDataType.STRING, UUID.randomUUID().toString());
            changed = true;
        }
        if (!pdc.has(shotsLeftKey, PersistentDataType.INTEGER)) {
            pdc.set(shotsLeftKey, PersistentDataType.INTEGER, cfg.maxShots());
            changed = true;
        }
        if (!pdc.has(loadedKey, PersistentDataType.INTEGER)) {
            pdc.set(loadedKey, PersistentDataType.INTEGER, 0);
            changed = true;
        }
        if (changed) {
            item.setItemMeta(meta);
            restoreDurabilityBar(item, cfg);
            replaceHand(player, hand, item);
        }
        return item;
    }

    private boolean isSameMusket(ItemStack item, UUID itemId, Settings cfg) {
        return isMusket(item, cfg) && itemId.equals(musketUuid(item));
    }

    private UUID musketUuid(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(musketUuidKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean isLoaded(ItemStack item) {
        return intValue(item, loadedKey, 0) > 0;
    }

    private void setLoaded(ItemStack item, boolean loaded) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(loadedKey, PersistentDataType.INTEGER, loaded ? 1 : 0);
        item.setItemMeta(meta);
    }

    private int shotsLeft(ItemStack item, Settings cfg) {
        return Math.max(0, Math.min(cfg.maxShots(), intValue(item, shotsLeftKey, cfg.maxShots())));
    }

    private void setShotsLeft(ItemStack item, int value) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(shotsLeftKey, PersistentDataType.INTEGER, Math.max(0, value));
        item.setItemMeta(meta);
    }

    private int intValue(ItemStack item, NamespacedKey key, int def) {
        if (item == null || !item.hasItemMeta()) return def;
        Integer value = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return value == null ? def : value;
    }

    private void restoreDurabilityBar(ItemStack item, Settings cfg) {
        int max = Math.max(1, cfg.maxShots());
        double usedFraction = 1.0D - (shotsLeft(item, cfg) / (double) max);
        setDurabilityFraction(item, usedFraction);
    }

    private void updateLoadingBar(ItemStack item, double progress) {
        setDurabilityFraction(item, 1.0D - Math.max(0.0D, Math.min(1.0D, progress)));
    }

    private void setDurabilityFraction(ItemStack item, double damageFraction) {
        if (item == null || item.getType().isAir()) return;
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return;
        short maxDurability = item.getType().getMaxDurability();
        if (maxDurability <= 0) return;
        int damage = (int) Math.round(Math.max(0.0D, Math.min(1.0D, damageFraction)) * maxDurability);
        damageable.setDamage(Math.min(maxDurability - 1, Math.max(0, damage)));
        item.setItemMeta(meta);
    }

    private ItemStack itemInHand(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
    }

    private void replaceHand(Player player, EquipmentSlot hand, ItemStack item) {
        if (hand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(item);
        else player.getInventory().setItemInMainHand(item);
    }

    private record LoadingState(UUID itemId, EquipmentSlot hand, BukkitTask task) { }
    private record BulletState(UUID shooterId, Location start, BukkitTask flightTask) { }
    private record HitResult(LivingEntity target) { }

    private record Settings(
            boolean enabled,
            String itemId,
            String ammoItemId,
            double reloadSeconds,
            int loadingBarIntervalTicks,
            int maxShots,
            double damageHearts,
            double armorIgnoredFraction,
            double fullDamageRange,
            double falloffBlocks,
            double falloffPercentPerStep,
            double projectileSpeedBlocksPerSecond,
            double projectileLifetimeSeconds,
            double projectileGravityPerTick,
            double smokeYOffset,
            double smokeForwardOffset,
            int smokeParticleCount,
            double projectileVisualScale,
            double projectileHitboxRadius,
            Sound reloadStartSound,
            float reloadStartVolume,
            float reloadStartPitch,
            Sound reloadFinishSound,
            float reloadFinishVolume,
            float reloadFinishPitch,
            Sound shotSound,
            float shotVolume,
            float shotPitch
    ) {
        private static Settings load(Plugin plugin) {
            File file = new File(plugin.getDataFolder(), "special-items.yml");
            if (!file.exists()) plugin.saveResource("special-items.yml", false);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection s = yaml.getConfigurationSection("weapons.musket");
            return new Settings(
                    bool(s, "enabled", true),
                    str(s, "item-id", "musket"),
                    str(s, "ammo-item-id", "musket_ammo"),
                    Math.max(0.1D, dbl(s, "reload-seconds", 5.0D)),
                    Math.max(1, integer(s, "loading-bar-interval-ticks", 5)),
                    Math.max(1, integer(s, "max-shots", 20)),
                    Math.max(0.0D, dbl(s, "damage-hearts", 20.0D)),
                    clamp(dbl(s, "armor-ignored-fraction", 0.5D), 0.0D, 1.0D),
                    Math.max(0.0D, dbl(s, "full-damage-range", 16.0D)),
                    Math.max(1.0D, dbl(s, "falloff-blocks", 8.0D)),
                    clamp(dbl(s, "falloff-percent-per-step", 10.0D), 0.0D, 99.0D),
                    Math.max(1.0D, dbl(s, "projectile-speed-blocks-per-second", 40.0D)),
                    Math.max(0.1D, dbl(s, "projectile-lifetime-seconds", 1.5D)),
                    Math.max(0.0D, dbl(s, "projectile-gravity-per-tick", 0.008D)),
                    dbl(s, "smoke-y-offset", 1.2D),
                    dbl(s, "smoke-forward-offset", 0.5D),
                    Math.max(1, integer(s, "smoke-particle-count", 50)),
                    clamp(dbl(s, "projectile-visual-scale", 0.2D), 0.05D, 2.0D),
                    clamp(dbl(s, "projectile-hitbox-radius", 0.35D), 0.05D, 2.0D),
                    sound(s, "sounds.reload-start", Sound.UI_BUTTON_CLICK),
                    flt(s, "sounds.reload-start-volume", 1.0F),
                    flt(s, "sounds.reload-start-pitch", 0.8F),
                    sound(s, "sounds.reload-finish", Sound.BLOCK_STONE_PRESSURE_PLATE_CLICK_ON),
                    flt(s, "sounds.reload-finish-volume", 1.0F),
                    flt(s, "sounds.reload-finish-pitch", 0.8F),
                    sound(s, "sounds.shot", Sound.ENTITY_GENERIC_EXPLODE),
                    flt(s, "sounds.shot-volume", 2.0F),
                    flt(s, "sounds.shot-pitch", 0.7F)
            );
        }

        private static String str(ConfigurationSection s, String key, String def) { return s == null ? def : s.getString(key, def); }
        private static int integer(ConfigurationSection s, String key, int def) { return s == null ? def : s.getInt(key, def); }
        private static double dbl(ConfigurationSection s, String key, double def) { return s == null ? def : s.getDouble(key, def); }
        private static float flt(ConfigurationSection s, String key, float def) { return (float) (s == null ? def : s.getDouble(key, def)); }
        private static boolean bool(ConfigurationSection s, String key, boolean def) { return s == null ? def : s.getBoolean(key, def); }
        private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
        private static Sound sound(ConfigurationSection s, String key, Sound def) {
            String raw = str(s, key, def.name());
            try {
                return Sound.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return def;
            }
        }
    }
}
