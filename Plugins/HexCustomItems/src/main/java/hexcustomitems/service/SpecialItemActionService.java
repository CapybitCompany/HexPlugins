package hexcustomitems.service;

import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.model.SpecialAction;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
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
    private final Random random = new Random();
    private final Map<UUID, Long> miningLuckUntil = new HashMap<>();
    private final Map<UUID, Long> fallProtectionUntil = new HashMap<>();

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

    private boolean redHeart(Player player, SpecialAction action) {
        int max = integer(action, "max", 5);
        if (!playerDataService.addRedHeart(player, max)) {
            messages.sendLimitReached(player);
            return false;
        }
        return true;
    }

    private boolean goldenHeart(Player player, SpecialAction action) {
        int maxHearts = integer(action, "max", 10);
        int addHearts = integer(action, "hearts", 1);
        int duration = integer(action, "duration-seconds", 10);
        double maxAbsorption = maxHearts * 2.0D;
        double current = player.getAbsorptionAmount();
        if (current >= maxAbsorption) {
            messages.sendLimitReached(player);
            return false;
        }
        double add = Math.min(addHearts * 2.0D, maxAbsorption - current);
        player.setAbsorptionAmount(current + add);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.setAbsorptionAmount(Math.max(0.0D, player.getAbsorptionAmount() - add));
        }, duration * 20L);
        return true;
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
        FishHook hook = player.launchProjectile(FishHook.class);
        hook.setVelocity(player.getLocation().getDirection().normalize().multiply(decimal(action, "velocity", 2.2D)));
        hook.getPersistentDataContainer().set(projectileKey, PersistentDataType.STRING, PROJECTILE_HOOK);
        player.getWorld().playSound(player.getLocation(), "minecraft:block.chain.place", 1.0F, 1.0F);
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
        target.sendMessage(Component.text("§cZostałeś oznaczony przez §f" + player.getName() + "§c."));
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
        double velocity = decimal(action, "velocity", 1.7D);
        Vector direction = player.getLocation().getDirection().normalize();
        player.setVelocity(direction.multiply(velocity).setY(Math.max(0.25D, direction.getY() * velocity + 0.2D)));
        int duration = integer(action, "speed-duration-seconds", 5);
        player.addPotionEffect(new PotionEffect(effect("speed"), duration * 20, 1, true, true, true));
        fallProtectionUntil.put(player.getUniqueId(), System.currentTimeMillis() + 6000L);
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
        for (BlockFace face : List.of(BlockFace.SELF, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block block = center.getBlock().getRelative(face);
            if (block.getType().isAir()) {
                block.setType(Material.COBWEB, false);
                changed.add(block);
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
            target.setVelocity(pull.normalize().multiply(1.8D).setY(0.35D));
        }
        target.addPotionEffect(new PotionEffect(effect("slowness"), 20, 1, true, true, true));
        play(shooter, "minecraft:item.totem.use", 1.0F, 1.3F);
        play(target, "minecraft:item.totem.use", 1.0F, 1.3F);
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

    private boolean bool(SpecialAction action, String key, boolean fallback) {
        String value = action.params().get(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
