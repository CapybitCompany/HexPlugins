package hexcustomitems.service;

import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.model.SpecialAction;
import hexcustomitems.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class SpecialItemActionService {

    private static final String PROJECTILE_SPIDER = "SPIDER_GRENADE";
    private static final String PROJECTILE_HOOK = "BUTCHER_HOOK";

    private final JavaPlugin plugin;
    private final CustomItemRegistryService registryService;
    private final PlayerDataService playerDataService;
    private final CombatIntegrationService combatIntegration;
    private final MessageService messages;
    private final NamespacedKey projectileKey;
    private final NamespacedKey projectileWebRadiusKey;
    private final NamespacedKey projectileWebLayersKey;
    private final NamespacedKey projectilePullStrengthKey;
    private final NamespacedKey projectileSlowTicksKey;
    private final NamespacedKey projectileSlowAmplifierKey;
    private final Random random = new Random();
    private final Map<UUID, Long> miningLuckUntil = new HashMap<>();
    private final Map<UUID, Long> fallProtectionUntil = new HashMap<>();
    private final Map<UUID, Integer> goldenHearts = new HashMap<>();

    public SpecialItemActionService(
            JavaPlugin plugin,
            CustomItemRegistryService registryService,
            PlayerDataService playerDataService,
            CombatIntegrationService combatIntegration,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.registryService = registryService;
        this.playerDataService = playerDataService;
        this.combatIntegration = combatIntegration;
        this.messages = messages;
        this.projectileKey = new NamespacedKey(plugin, "special_projectile");
        this.projectileWebRadiusKey = new NamespacedKey(plugin, "special_projectile_web_radius");
        this.projectileWebLayersKey = new NamespacedKey(plugin, "special_projectile_web_layers");
        this.projectilePullStrengthKey = new NamespacedKey(plugin, "special_projectile_pull_strength");
        this.projectileSlowTicksKey = new NamespacedKey(plugin, "special_projectile_slow_ticks");
        this.projectileSlowAmplifierKey = new NamespacedKey(plugin, "special_projectile_slow_amplifier");
    }

    public boolean execute(Player player, EquipmentSlot hand, CustomItemDefinition definition, SpecialAction action) {
        if (bool(action, "requires-out-of-combat", false) && combatIntegration.isInCombat(player)) {
            messages.sendCombatBlocked(player);
            return false;
        }
        return switch (action.kind()) {
            case "RED_HEART" -> redHeart(player, action);
            case "GOLDEN_HEART" -> goldenHeart(player, action);
            case "DARKNESS_POWDER" -> darkness(player, action);
            case "SPIDER_GRENADE" -> spiderGrenade(player, action);
            case "PHOENIX_HEART" -> phoenixHeart(player, action);
            case "BUTCHER_HOOK" -> butcherHook(player, action);
            case "MINING_LUCK" -> miningLuck(player, action);
            case "HUNTER_SKULL" -> hunterSkull(player, action);
            case "KINETIC_CHARGE" -> kineticCharge(player, action);
            case "INVISIBILITY_COOKIE" -> invisibilityCookie(player, action);
            default -> {
                plugin.getLogger().warning("Nieznana akcja specjalna: " + action.kind());
                yield false;
            }
        };
    }

    public void handleProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        String kind = projectile.getPersistentDataContainer().get(projectileKey, PersistentDataType.STRING);
        if (kind == null) {
            return;
        }
        if (PROJECTILE_SPIDER.equals(kind)) {
            handleSpiderImpact(projectile, event);
        } else if (PROJECTILE_HOOK.equals(kind)) {
            handleHookImpact(projectile, event);
        }
        projectile.remove();
    }

    public void handleBlockDrops(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        Long until = miningLuckUntil.get(player.getUniqueId());
        if (until == null || until <= System.currentTimeMillis()) {
            miningLuckUntil.remove(player.getUniqueId());
            return;
        }
        if (!isOre(event.getBlockState().getType()) || event.getItems().isEmpty()) {
            return;
        }
        if (random.nextDouble() >= 0.20D) {
            return;
        }
        ItemStack extra = event.getItems().getFirst().getItemStack().clone();
        extra.setAmount(1);
        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5D, 0.5D, 0.5D), extra);
    }

    public void handleFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        Long until = fallProtectionUntil.get(player.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) {
            event.setCancelled(true);
            fallProtectionUntil.remove(player.getUniqueId());
        }
    }

    public void handleProjectileDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile)) {
            return;
        }
        String kind = projectile.getPersistentDataContainer().get(projectileKey, PersistentDataType.STRING);
        if (PROJECTILE_HOOK.equals(kind)) {
            event.setCancelled(true);
        }
    }

    private boolean redHeart(Player player, SpecialAction action) {
        int max = integer(action, "max", 5);
        if (!playerDataService.addRedHeart(player, max)) {
            messages.sendLimitReached(player);
            return false;
        }
        return true;
    }

    private boolean goldenHeart(Player player, SpecialAction action) {
        int maxHearts = Math.max(1, integer(action, "max", 10));
        int addHearts = Math.max(1, integer(action, "hearts", 1));
        int duration = Math.max(1, integer(action, "duration-seconds", 10));
        UUID playerId = player.getUniqueId();
        int currentHearts = goldenHearts.getOrDefault(playerId, 0);
        if (currentHearts >= maxHearts) {
            messages.sendLimitReached(player);
            return false;
        }
        int grantedHearts = Math.min(addHearts, maxHearts - currentHearts);
        double grantedAbsorption = grantedHearts * 2.0D;
        goldenHearts.put(playerId, currentHearts + grantedHearts);
        player.setAbsorptionAmount(player.getAbsorptionAmount() + grantedAbsorption);
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> expireGoldenHearts(playerId, grantedHearts, grantedAbsorption),
                duration * 20L);
        return true;
    }

    private void expireGoldenHearts(UUID playerId, int hearts, double absorption) {
        int currentHearts = goldenHearts.getOrDefault(playerId, 0);
        int remainingHearts = Math.max(0, currentHearts - hearts);
        if (remainingHearts == 0) {
            goldenHearts.remove(playerId);
        } else {
            goldenHearts.put(playerId, remainingHearts);
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            player.setAbsorptionAmount(Math.max(0.0D, player.getAbsorptionAmount() - absorption));
        }
    }

    private boolean darkness(Player player, SpecialAction action) {
        double radius = decimal(action, "radius", 5.0D);
        int duration = integer(action, "duration-seconds", 2);
        PotionEffectType blindness = effect("blindness");
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof Player target && !target.getUniqueId().equals(player.getUniqueId())) {
                target.addPotionEffect(new PotionEffect(blindness, duration * 20, 0, true, true, true));
                play(target, string(action, "sound", "minecraft:entity.vex.charge"), 1.0F, 1.0F);
            }
        }
        play(player, string(action, "sound", "minecraft:entity.vex.charge"), 1.0F, 1.0F);
        return true;
    }

    private boolean spiderGrenade(Player player, SpecialAction action) {
        Snowball snowball = player.launchProjectile(Snowball.class);
        snowball.setVelocity(player.getLocation().getDirection().normalize().multiply(decimal(action, "velocity", 1.4D)));
        snowball.getPersistentDataContainer().set(projectileKey, PersistentDataType.STRING, PROJECTILE_SPIDER);
        snowball.getPersistentDataContainer().set(projectileWebRadiusKey, PersistentDataType.INTEGER, integer(action, "web-radius", 2));
        snowball.getPersistentDataContainer().set(projectileWebLayersKey, PersistentDataType.INTEGER, integer(action, "web-layers", 2));
        player.getWorld().playSound(player.getLocation(), "minecraft:entity.snowball.throw", 1.0F, 1.0F);
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> player.getWorld().playSound(player.getLocation(), "minecraft:block.cobweb.break", 0.8F, 1.0F),
                20L);
        return true;
    }

    private boolean phoenixHeart(Player player, SpecialAction action) {
        int duration = integer(action, "duration-seconds", 300);
        int amplifier = integer(action, "amplifier", 0);
        player.addPotionEffect(new PotionEffect(effect("regeneration"), duration * 20, amplifier, true, true, true));
        return true;
    }

    private boolean butcherHook(Player player, SpecialAction action) {
        Arrow hook = player.launchProjectile(Arrow.class);
        hook.setVelocity(player.getEyeLocation().getDirection().normalize().multiply(decimal(action, "velocity", 3.2D)));
        hook.setDamage(0.0D);
        hook.setCritical(false);
        hook.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        hook.getPersistentDataContainer().set(projectileKey, PersistentDataType.STRING, PROJECTILE_HOOK);
        hook.getPersistentDataContainer().set(projectilePullStrengthKey, PersistentDataType.DOUBLE, decimal(action, "pull-strength", 2.7D));
        hook.getPersistentDataContainer().set(projectileSlowTicksKey, PersistentDataType.INTEGER, integer(action, "slow-duration-seconds", 3) * 20);
        hook.getPersistentDataContainer().set(projectileSlowAmplifierKey, PersistentDataType.INTEGER, integer(action, "slow-amplifier", 3));
        player.getWorld().playSound(player.getLocation(), "minecraft:entity.arrow.shoot", 1.0F, 0.85F);
        player.getWorld().playSound(player.getLocation(), "minecraft:entity.fishing_bobber.throw", 1.0F, 0.7F);
        player.getWorld().playSound(player.getLocation(), "minecraft:block.chain.place", 1.0F, 1.15F);
        return true;
    }

    private boolean miningLuck(Player player, SpecialAction action) {
        int duration = integer(action, "duration-seconds", 180);
        miningLuckUntil.put(player.getUniqueId(), System.currentTimeMillis() + duration * 1000L);
        player.addPotionEffect(new PotionEffect(effect("luck"), duration * 20, 0, true, true, true));
        return true;
    }

    private boolean hunterSkull(Player player, SpecialAction action) {
        int duration = integer(action, "duration-seconds", 10);
        double maxDistance = decimal(action, "max-distance", 512.0D);
        Player target = nearestPlayer(player, maxDistance);
        if (target == null) {
            messages.sendNoTarget(player);
            return false;
        }
        play(player, string(action, "sound", "minecraft:entity.wither.spawn"), 1.0F, 1.0F);
        play(target, string(action, "sound", "minecraft:entity.wither.spawn"), 1.0F, 1.0F);
        target.sendActionBar(TextUtil.parse("&cZostales oznaczony przez &f" + player.getName() + "&c."));
        int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
            private int ticks;

            @Override
            public void run() {
                if (!player.isOnline() || !target.isOnline() || ticks > duration * 20) {
                    return;
                }
                double distance = player.getLocation().distance(target.getLocation());
                player.sendActionBar(Component.text("§cWidzisz drogę do §f" + target.getName()
                        + "§8 | §eOdległość: §f" + Math.round(distance)));
                drawPath(player, target);
                ticks += 10;
            }
        }, 0L, 10L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.getScheduler().cancelTask(taskId), duration * 20L + 2L);
        return true;
    }

    private boolean kineticCharge(Player player, SpecialAction action) {
        double horizontalVelocity = decimal(action, "horizontal-velocity", decimal(action, "velocity", 3.4D));
        double baseUpwardVelocity = decimal(action, "base-upward-velocity", 0.35D);
        double upwardLookScale = decimal(action, "upward-look-scale", 0.35D);
        double maxUpwardVelocity = decimal(action, "max-upward-velocity", 0.75D);
        Vector direction = player.getLocation().getDirection().normalize();
        Vector horizontal = new Vector(direction.getX(), 0.0D, direction.getZ());
        if (horizontal.lengthSquared() <= 0.0001D) {
            Location facing = player.getLocation();
            facing.setPitch(0.0F);
            horizontal = facing.getDirection();
        }
        double upwardVelocity = Math.min(maxUpwardVelocity, baseUpwardVelocity + Math.max(0.0D, direction.getY()) * upwardLookScale);
        player.setVelocity(horizontal.normalize().multiply(horizontalVelocity).setY(Math.max(0.2D, upwardVelocity)));
        int duration = integer(action, "speed-duration-seconds", 5);
        player.addPotionEffect(new PotionEffect(effect("speed"), duration * 20, 1, true, true, true));
        fallProtectionUntil.put(player.getUniqueId(), System.currentTimeMillis() + 10000L);
        return true;
    }

    private boolean invisibilityCookie(Player player, SpecialAction action) {
        int duration = integer(action, "duration-seconds", 10);
        player.addPotionEffect(new PotionEffect(effect("invisibility"), duration * 20, 0, true, true, true));
        return true;
    }

    private void handleSpiderImpact(Projectile projectile, ProjectileHitEvent event) {
        Location center = impactLocation(projectile, event);
        List<Block> changed = new ArrayList<>();
        int radius = Math.max(1, projectileInteger(projectile, projectileWebRadiusKey, 2));
        int layers = Math.max(1, projectileInteger(projectile, projectileWebLayersKey, 2));
        for (int y = 0; y < layers; y++) {
            int layerRadius = Math.max(0, radius - y);
            for (int x = -layerRadius; x <= layerRadius; x++) {
                for (int z = -layerRadius; z <= layerRadius; z++) {
                    if (Math.abs(x) + Math.abs(z) > layerRadius) {
                        continue;
                    }
                    Block block = center.getBlock().getRelative(x, y, z);
                    if (block.getType().isAir()) {
                        block.setType(Material.COBWEB, false);
                        changed.add(block);
                    }
                }
            }
        }
        center.getWorld().playSound(center, "minecraft:block.cobweb.break", 1.0F, 0.8F);
        center.getWorld().playSound(center, "minecraft:block.cobweb.break", 1.0F, 1.0F);
        center.getWorld().playSound(center, "minecraft:block.cobweb.break", 1.0F, 1.2F);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Block block : changed) {
                if (block.getType() == Material.COBWEB) {
                    block.setType(Material.AIR, false);
                }
            }
        }, 100L);
    }

    private void handleHookImpact(Projectile projectile, ProjectileHitEvent event) {
        if (!(projectile.getShooter() instanceof Player shooter)) {
            return;
        }
        if (!(event.getHitEntity() instanceof Player target) || target.getUniqueId().equals(shooter.getUniqueId())) {
            return;
        }
        Vector pull = shooter.getLocation().toVector().subtract(target.getLocation().toVector());
        if (pull.lengthSquared() > 0.0D) {
            double strength = projectileDouble(projectile, projectilePullStrengthKey, 2.7D);
            target.setVelocity(pull.normalize().multiply(strength).setY(0.42D));
        }
        int slowTicks = projectileInteger(projectile, projectileSlowTicksKey, 60);
        int slowAmplifier = projectileInteger(projectile, projectileSlowAmplifierKey, 3);
        target.addPotionEffect(new PotionEffect(effect("slowness"), slowTicks, slowAmplifier, true, true, true));
        play(shooter, "minecraft:block.chain.break", 1.0F, 1.0F);
        play(target, "minecraft:block.chain.break", 1.0F, 1.0F);
        play(shooter, "minecraft:entity.fishing_bobber.retrieve", 1.0F, 0.8F);
        play(target, "minecraft:entity.fishing_bobber.retrieve", 1.0F, 0.8F);
    }

    private Location impactLocation(Projectile projectile, ProjectileHitEvent event) {
        if (event.getHitBlock() != null) {
            return event.getHitBlock().getLocation().add(0.5D, 1.0D, 0.5D);
        }
        if (event.getHitEntity() != null) {
            return event.getHitEntity().getLocation();
        }
        return projectile.getLocation();
    }

    private Player nearestPlayer(Player player, double maxDistance) {
        return player.getWorld().getPlayers().stream()
                .filter(other -> !other.getUniqueId().equals(player.getUniqueId()))
                .filter(other -> !other.isDead())
                .filter(other -> other.getLocation().distanceSquared(player.getLocation()) <= maxDistance * maxDistance)
                .min(Comparator.comparingDouble(other -> other.getLocation().distanceSquared(player.getLocation())))
                .orElse(null);
    }

    private void drawPath(Player player, Player target) {
        Location start = player.getEyeLocation();
        Vector direction = target.getLocation().toVector().subtract(start.toVector());
        if (direction.lengthSquared() == 0.0D) {
            return;
        }
        direction.normalize();
        for (int i = 1; i <= 8; i++) {
            Location point = start.clone().add(direction.clone().multiply(i * 0.8D));
            player.spawnParticle(Particle.FLAME, point, 1, 0.02D, 0.02D, 0.02D, 0.0D);
        }
    }

    private boolean isOre(Material material) {
        String name = material.name().toLowerCase(Locale.ROOT);
        return name.endsWith("_ore") || name.equals("ancient_debris");
    }

    private PotionEffectType effect(String key) {
        PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(key));
        if (type == null) {
            throw new IllegalStateException("Missing potion effect: " + key);
        }
        return type;
    }

    private void play(Player player, String sound, float volume, float pitch) {
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    private String string(SpecialAction action, String key, String fallback) {
        return action.params().getOrDefault(key, fallback);
    }

    private int integer(SpecialAction action, String key, int fallback) {
        try {
            return Integer.parseInt(action.params().getOrDefault(key, String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private double decimal(SpecialAction action, String key, double fallback) {
        try {
            return Double.parseDouble(action.params().getOrDefault(key, String.valueOf(fallback)));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int projectileInteger(Projectile projectile, NamespacedKey key, int fallback) {
        Integer value = projectile.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return value == null ? fallback : value;
    }

    private double projectileDouble(Projectile projectile, NamespacedKey key, double fallback) {
        Double value = projectile.getPersistentDataContainer().get(key, PersistentDataType.DOUBLE);
        return value == null ? fallback : value;
    }

    private boolean bool(SpecialAction action, String key, boolean fallback) {
        String value = action.params().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
