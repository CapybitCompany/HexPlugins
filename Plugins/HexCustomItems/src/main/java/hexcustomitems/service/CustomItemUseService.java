package hexcustomitems.service;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.model.CustomItemEffectType;
import hexcustomitems.model.ItemEffectSettings;
import hexcustomitems.model.PotionEffectSpec;
import hexcustomitems.util.PlaceholderUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class CustomItemUseService {

    private static final String PROJECTILE_TYPE_WIND = "wind";
    private static final String PROJECTILE_TYPE_AREA = "area";

    private final JavaPlugin plugin;
    private final Supplier<HexCustomItemsConfig> configSupplier;
    private final CustomItemRegistryService registryService;
    private final MessageService messageService;
    private final NamespacedKey projectileKindKey;
    private final NamespacedKey projectileItemIdKey;
    private final NamespacedKey projectileOwnerKey;
    private final NamespacedKey projectileSourceXKey;
    private final NamespacedKey projectileSourceZKey;

    public CustomItemUseService(
            JavaPlugin plugin,
            Supplier<HexCustomItemsConfig> configSupplier,
            CustomItemRegistryService registryService,
            MessageService messageService
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.registryService = Objects.requireNonNull(registryService, "registryService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
        this.projectileKindKey = new NamespacedKey(plugin, "hci_projectile_kind");
        this.projectileItemIdKey = new NamespacedKey(plugin, "hci_projectile_item_id");
        this.projectileOwnerKey = new NamespacedKey(plugin, "hci_projectile_owner");
        this.projectileSourceXKey = new NamespacedKey(plugin, "hci_projectile_source_x");
        this.projectileSourceZKey = new NamespacedKey(plugin, "hci_projectile_source_z");
    }

    public boolean tryUseItem(Player player, EquipmentSlot hand, ItemStack item, Action action, Block clickedBlock) {
        String itemId = registryService.resolveItemId(item);
        if (itemId == null) {
            return false;
        }

        CustomItemDefinition definition = registryService.findById(itemId);
        if (definition == null) {
            return false;
        }

        ItemEffectSettings effect = definition.effect();
        return switch (effect.type()) {
            case HEX_COINS -> useHexCoins(player, hand, definition);
            case SELF_POTION -> useSelfPotion(player, hand, definition);
            case AREA_PROJECTILE_POTION -> useAreaProjectilePotion(player, hand, definition);
            case DARKNESS_AOE -> useDarknessAoE(player, hand, definition);
            case TARGET_FIRE -> useTargetFire(player, hand, definition);
            case TARGET_POTION -> useTargetPotion(player, hand, definition);
            case WIND_CHARGE -> useWindCharge(player, hand, definition, clickedBlock);
        };
    }

    public void handleProjectileHit(Projectile projectile, Location hitLocation) {
        PersistentDataContainer data = projectile.getPersistentDataContainer();
        String kind = data.get(projectileKindKey, PersistentDataType.STRING);
        if (kind == null || kind.isBlank()) {
            return;
        }

        if (PROJECTILE_TYPE_AREA.equals(kind)) {
            handleAreaProjectileHit(projectile, hitLocation);
            return;
        }

        if (PROJECTILE_TYPE_WIND.equals(kind)) {
            handleWindProjectileHit(projectile, hitLocation);
        }
    }

    private boolean useHexCoins(Player player, EquipmentSlot hand, CustomItemDefinition definition) {
        if (!consumeOne(player, hand)) {
            return false;
        }

        ItemEffectSettings effect = definition.effect();
        String commandTemplate = effect.commandTemplate().isBlank()
                ? "eco give %player% %coins%"
                : effect.commandTemplate();
        String command = PlaceholderUtil.apply(
                commandTemplate,
                Map.of("player", player.getName(), "coins", String.valueOf(effect.coins()))
        );
        Bukkit.dispatchCommand(plugin.getServer().getConsoleSender(), command);
        playSound(player, configSupplier.get().sounds().consume(), 1.0F, 1.0F);
        return true;
    }

    private boolean useSelfPotion(Player player, EquipmentSlot hand, CustomItemDefinition definition) {
        if (!consumeOne(player, hand)) {
            return false;
        }

        PotionEffectSpec effect = definition.effect().potionEffect();
        if (effect != null) {
            player.addPotionEffect(new PotionEffect(
                    effect.type(),
                    effect.durationSeconds() * 20,
                    effect.amplifier(),
                    true,
                    true,
                    true
            ));
        }
        playSound(player, configSupplier.get().sounds().drink(), 1.0F, 1.0F);
        return true;
    }

    private boolean useAreaProjectilePotion(Player player, EquipmentSlot hand, CustomItemDefinition definition) {
        if (!consumeOne(player, hand)) {
            return false;
        }

        ThrownPotion projectile = player.launchProjectile(ThrownPotion.class);
        projectile.setItem(registryService.createItem(definition, 1));
        markAreaProjectile(projectile, definition.id());
        playSound(player, configSupplier.get().sounds().throwSound(), 1.0F, 0.9F);
        return true;
    }

    private boolean useDarknessAoE(Player player, EquipmentSlot hand, CustomItemDefinition definition) {
        if (!consumeOne(player, hand)) {
            return false;
        }

        ItemEffectSettings settings = definition.effect();
        applyAreaEffects(
                player,
                player.getLocation(),
                settings.radius(),
                settings.areaEffects(),
                settings.affectSelf()
        );
        playSound(player, configSupplier.get().sounds().dark(), 1.0F, 1.0F);
        return true;
    }

    private boolean useTargetFire(Player player, EquipmentSlot hand, CustomItemDefinition definition) {
        ItemEffectSettings settings = definition.effect();
        Player target = resolveTargetPlayer(player, settings.maxDistance());
        if (target == null) {
            messageService.sendTargetPlayerRequired(player);
            return true;
        }

        if (target.getLocation().distanceSquared(player.getLocation()) > settings.maxDistance() * settings.maxDistance()) {
            messageService.sendTargetTooFar(player);
            return true;
        }

        if (isNegativeEffectBlocked(target)) {
            messageService.sendTargetOpProtected(player);
            return true;
        }

        if (!consumeOne(player, hand)) {
            return false;
        }

        target.setFireTicks(settings.fireSeconds() * 20);
        playSound(target, configSupplier.get().sounds().fire(), 1.0F, 1.1F);
        return true;
    }

    private boolean useTargetPotion(Player player, EquipmentSlot hand, CustomItemDefinition definition) {
        ItemEffectSettings settings = definition.effect();
        Player target = resolveTargetPlayer(player, settings.maxDistance());
        if (target == null) {
            messageService.sendTargetPlayerRequired(player);
            return true;
        }

        if (target.getLocation().distanceSquared(player.getLocation()) > settings.maxDistance() * settings.maxDistance()) {
            messageService.sendTargetTooFar(player);
            return true;
        }

        if (isNegativeEffectBlocked(target)) {
            messageService.sendTargetOpProtected(player);
            return true;
        }

        if (!consumeOne(player, hand)) {
            return false;
        }

        PotionEffectSpec effect = settings.potionEffect();
        if (effect != null) {
            target.addPotionEffect(new PotionEffect(
                    effect.type(),
                    effect.durationSeconds() * 20,
                    effect.amplifier(),
                    true,
                    true,
                    true
            ));
        }
        playSound(target, configSupplier.get().sounds().ice(), 1.0F, 0.7F);
        return true;
    }

    private boolean useWindCharge(Player player, EquipmentSlot hand, CustomItemDefinition definition, Block clickedBlock) {
        if (clickedBlock != null && clickedBlock.getType().name().contains("BUTTON")) {
            return true;
        }

        if (!consumeOne(player, hand)) {
            return false;
        }

        HexCustomItemsConfig.WindSettings wind = configSupplier.get().windSettings();
        Vector direction = player.getLocation().getDirection().normalize();

        Snowball projectile = player.launchProjectile(Snowball.class);
        projectile.setVelocity(direction.multiply(wind.projectileSpeed()));
        markWindProjectile(projectile, definition.id(), player.getUniqueId(), player.getLocation());

        Vector recoil = player.getLocation().getDirection().normalize().multiply(-wind.recoil());
        recoil.setY(wind.recoilUp());
        player.setVelocity(player.getVelocity().add(recoil));

        playSound(player, configSupplier.get().sounds().windLaunch(), 0.8F, 1.35F);
        return true;
    }

    private void handleAreaProjectileHit(Projectile projectile, Location hitLocation) {
        String itemId = projectile.getPersistentDataContainer().get(projectileItemIdKey, PersistentDataType.STRING);
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        CustomItemDefinition definition = registryService.findById(itemId);
        if (definition == null) {
            return;
        }

        ItemEffectSettings settings = definition.effect();
        PotionEffectSpec potion = settings.potionEffect();
        if (potion == null) {
            return;
        }

        Player owner = projectile.getShooter() instanceof Player p ? p : null;
        applyAreaEffects(owner, hitLocation, settings.radius(), List.of(potion), settings.affectSelf());
        if (owner != null) {
            playSound(owner, configSupplier.get().sounds().throwSound(), 1.0F, 0.9F);
        }
    }

    private void handleWindProjectileHit(Projectile projectile, Location hitLocation) {
        String ownerRaw = projectile.getPersistentDataContainer().get(projectileOwnerKey, PersistentDataType.STRING);
        if (ownerRaw == null || ownerRaw.isBlank()) {
            return;
        }

        UUID ownerId;
        try {
            ownerId = UUID.fromString(ownerRaw);
        } catch (IllegalArgumentException ex) {
            return;
        }

        Double sourceX = projectile.getPersistentDataContainer().get(projectileSourceXKey, PersistentDataType.DOUBLE);
        Double sourceZ = projectile.getPersistentDataContainer().get(projectileSourceZKey, PersistentDataType.DOUBLE);
        if (sourceX == null || sourceZ == null) {
            return;
        }

        HexCustomItemsConfig.WindSettings wind = configSupplier.get().windSettings();
        hitLocation.getWorld().spawnParticle(Particle.EXPLOSION, hitLocation, wind.particleExplosionCount(), 0.4, 0.25, 0.4, 0.0);
        hitLocation.getWorld().spawnParticle(Particle.CLOUD, hitLocation, Math.max(8, wind.particleExplosionCount() * 10), 0.7, 0.2, 0.7, 0.02);
        hitLocation.getWorld().playSound(hitLocation, configSupplier.get().sounds().windHit(), 0.9F, 0.9F);

        Set<UUID> hitPlayers = new HashSet<>();
        for (Entity entity : hitLocation.getWorld().getNearbyEntities(hitLocation, wind.radius(), wind.radius(), wind.radius())) {
            if (!(entity instanceof Player target)) {
                continue;
            }

            if (isNegativeEffectBlocked(target)) {
                continue;
            }

            applyWindVelocity(target, hitLocation, sourceX, sourceZ, ownerId, wind);
            hitPlayers.add(target.getUniqueId());
        }

        Location upper = hitLocation.clone().add(0.0D, 0.9D, 0.0D);
        for (Entity entity : upper.getWorld().getNearbyEntities(upper, wind.radius(), wind.radius(), wind.radius())) {
            if (!(entity instanceof Player target)) {
                continue;
            }
            if (hitPlayers.contains(target.getUniqueId())) {
                continue;
            }
            if (isNegativeEffectBlocked(target)) {
                continue;
            }
            applyWindVelocity(target, upper, sourceX, sourceZ, ownerId, wind);
        }
    }

    private void applyAreaEffects(
            Player owner,
            Location center,
            double radius,
            List<PotionEffectSpec> effects,
            boolean affectSelf
    ) {
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof Player target)) {
                continue;
            }

            if (!affectSelf && owner != null && target.getUniqueId().equals(owner.getUniqueId())) {
                continue;
            }
            if (isNegativeEffectBlocked(target)) {
                continue;
            }

            for (PotionEffectSpec effect : effects) {
                target.addPotionEffect(new PotionEffect(
                        effect.type(),
                        effect.durationSeconds() * 20,
                        effect.amplifier(),
                        true,
                        true,
                        true
                ));
            }
        }
    }

    private void applyWindVelocity(
            Player target,
            Location origin,
            double sourceX,
            double sourceZ,
            UUID ownerId,
            HexCustomItemsConfig.WindSettings wind
    ) {
        double dx = target.getLocation().getX() - origin.getX();
        double dz = target.getLocation().getZ() - origin.getZ();

        if (Math.abs(dx) < 0.20D && Math.abs(dz) < 0.20D) {
            dx = target.getLocation().getX() - sourceX;
            dz = target.getLocation().getZ() - sourceZ;
        }

        dx = clamp(dx, -2.4D, 2.4D);
        dz = clamp(dz, -2.4D, 2.4D);

        boolean owner = target.getUniqueId().equals(ownerId);
        double power = owner ? wind.powerOwner() : wind.power();
        double up = owner ? wind.upOwner() : wind.up();

        double vx = applyMinimumPush(dx * power, dx, 0.40D);
        double vz = applyMinimumPush(dz * power, dz, 0.40D);

        target.setVelocity(new Vector(vx, up, vz));
    }

    private double applyMinimumPush(double value, double axis, double minimum) {
        if (axis < 0) {
            return (value + minimum > 0) ? -minimum : value;
        }
        return (value - minimum < 0) ? minimum : value;
    }

    private Player resolveTargetPlayer(Player player, double maxDistance) {
        Entity targetEntity = player.getTargetEntity((int) Math.ceil(maxDistance));
        if (targetEntity instanceof Player targetPlayer) {
            return targetPlayer;
        }
        return null;
    }

    private boolean isNegativeEffectBlocked(Player player) {
        return configSupplier.get().protectOpsFromNegativeEffects() && player.isOp();
    }

    private void markAreaProjectile(Projectile projectile, String itemId) {
        PersistentDataContainer data = projectile.getPersistentDataContainer();
        data.set(projectileKindKey, PersistentDataType.STRING, PROJECTILE_TYPE_AREA);
        data.set(projectileItemIdKey, PersistentDataType.STRING, itemId);
    }

    private void markWindProjectile(Snowball projectile, String itemId, UUID owner, Location source) {
        PersistentDataContainer data = projectile.getPersistentDataContainer();
        data.set(projectileKindKey, PersistentDataType.STRING, PROJECTILE_TYPE_WIND);
        data.set(projectileItemIdKey, PersistentDataType.STRING, itemId);
        data.set(projectileOwnerKey, PersistentDataType.STRING, owner.toString());
        data.set(projectileSourceXKey, PersistentDataType.DOUBLE, source.getX());
        data.set(projectileSourceZKey, PersistentDataType.DOUBLE, source.getZ());
    }

    private boolean consumeOne(Player player, EquipmentSlot hand) {
        ItemStack current = player.getInventory().getItem(hand);
        if (current == null || current.getType().isAir()) {
            return false;
        }

        int amount = current.getAmount();
        if (amount <= 1) {
            player.getInventory().setItem(hand, null);
        } else {
            current.setAmount(amount - 1);
            player.getInventory().setItem(hand, current);
        }
        return true;
    }

    private void playSound(Player player, String soundName, float volume, float pitch) {
        if (soundName == null || soundName.isBlank()) {
            return;
        }
        player.playSound(player.getLocation(), soundName, volume, pitch);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
