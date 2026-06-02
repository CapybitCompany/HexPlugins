package hexcustommobs.service;

import hexcustommobs.config.HexCustomMobsConfig;
import hexcustommobs.integration.HexCustomItemsBridge;
import hexcustommobs.util.LegacyFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class CustomMobService {

    private final Plugin plugin;
    private final Supplier<HexCustomMobsConfig> configSupplier;
    private final HexCustomItemsBridge customItemsBridge;
    private final NamespacedKey mobIdKey;

    public CustomMobService(
            Plugin plugin,
            Supplier<HexCustomMobsConfig> configSupplier,
            HexCustomItemsBridge customItemsBridge
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
        this.customItemsBridge = Objects.requireNonNull(customItemsBridge, "customItemsBridge");
        this.mobIdKey = new NamespacedKey(plugin, "custom_mob_id");
    }

    public Optional<String> getCustomMobId(LivingEntity entity) {
        String mobId = entity.getPersistentDataContainer().get(mobIdKey, PersistentDataType.STRING);
        if (mobId == null || mobId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(mobId);
    }

    public boolean isCustomMob(LivingEntity entity) {
        return getCustomMobId(entity).isPresent();
    }

    public String pickMobId(HexCustomMobsConfig.BiomeRule rule) {
        Map<String, Integer> weights = rule.mobWeights();
        if (weights.isEmpty()) {
            return null;
        }
        int total = 0;
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            if (configSupplier.get().mobs().containsKey(entry.getKey())) {
                total += entry.getValue();
            }
        }
        if (total <= 0) {
            return null;
        }
        int roll = ThreadLocalRandom.current().nextInt(total) + 1;
        int current = 0;
        for (Map.Entry<String, Integer> entry : weights.entrySet()) {
            if (!configSupplier.get().mobs().containsKey(entry.getKey())) {
                continue;
            }
            current += entry.getValue();
            if (roll <= current) {
                return entry.getKey();
            }
        }
        return null;
    }

    public HexCustomMobsConfig.MobTemplate getTemplate(String mobId) {
        return configSupplier.get().mobs().get(mobId);
    }

    public LivingEntity spawnCustomMob(Location location, String mobId) {
        HexCustomMobsConfig.MobTemplate template = configSupplier.get().mobs().get(mobId);
        if (template == null) {
            return null;
        }
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        Class<? extends Entity> entityClass = template.type().getEntityClass();
        if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Class<? extends LivingEntity> livingClass = (Class<? extends LivingEntity>) entityClass;

        LivingEntity spawned = world.spawn(location, livingClass, CreatureSpawnEvent.SpawnReason.CUSTOM, false, entity -> {
        });
        applyTemplate(spawned, mobId, template);
        return spawned;
    }

    public void applyTemplate(LivingEntity entity, String mobId, HexCustomMobsConfig.MobTemplate template) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(mobIdKey, PersistentDataType.STRING, mobId);

        entity.setCanPickupItems(false);
        entity.setRemoveWhenFarAway(true);

        for (Map.Entry<Attribute, Double> entry : template.combatAttributes().entrySet()) {
            AttributeInstance instance = entity.getAttribute(entry.getKey());
            if (instance == null) {
                continue;
            }
            instance.setBaseValue(entry.getValue());
        }

        AttributeInstance maxHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double targetHealth = template.maxHealth();
        if (maxHealth != null) {
            targetHealth = maxHealth.getBaseValue();
        }
        entity.setHealth(Math.max(1.0D, targetHealth));

        applyEquipment(entity, template.equipment());
        updateHpBar(entity);
    }

    public void updateHpBar(LivingEntity entity) {
        Optional<String> mobIdOptional = getCustomMobId(entity);
        if (mobIdOptional.isEmpty()) {
            return;
        }
        HexCustomMobsConfig config = configSupplier.get();
        HexCustomMobsConfig.HpBar hpBar = config.hpBar();
        if (!hpBar.enabled()) {
            entity.setCustomNameVisible(false);
            return;
        }

        HexCustomMobsConfig.MobTemplate template = config.mobs().get(mobIdOptional.get());
        if (template == null) {
            return;
        }

        double maxHealth = template.maxHealth();
        AttributeInstance attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) {
            maxHealth = attr.getValue();
        }
        double currentHealth = Math.max(0.0D, Math.min(entity.getHealth(), maxHealth));
        String healthText = LegacyFormat.number(currentHealth, hpBar.showDecimals());
        String maxHealthText = LegacyFormat.number(maxHealth, hpBar.showDecimals());
        String barText = healthBar(hpBar, currentHealth, maxHealth);

        String formatted = hpBar.format()
                .replace("<name>", template.displayName())
                .replace("<health>", healthText)
                .replace("<max_health>", maxHealthText)
                .replace("<bar>", barText);

        Component name = LegacyFormat.component(formatted);
        entity.customName(name);
        entity.setCustomNameVisible(true);
    }

    public List<ItemStack> rollDrops(String mobId) {
        HexCustomMobsConfig.MobTemplate template = configSupplier.get().mobs().get(mobId);
        if (template == null || template.drops().isEmpty()) {
            return List.of();
        }
        List<ItemStack> drops = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (HexCustomMobsConfig.DropDefinition drop : template.drops()) {
            if (random.nextDouble() > drop.chance()) {
                continue;
            }
            ItemStack item = toItemStack(drop.item());
            if (item == null) {
                continue;
            }
            int amount = random.nextInt(drop.minAmount(), drop.maxAmount() + 1);
            item.setAmount(amount);
            drops.add(item);
        }
        return List.copyOf(drops);
    }

    public int resolveExpDrop(String mobId) {
        HexCustomMobsConfig.MobTemplate template = configSupplier.get().mobs().get(mobId);
        if (template == null) {
            return 0;
        }
        return template.expDrop();
    }

    public boolean exceedsChunkLimit(Location location) {
        HexCustomMobsConfig config = configSupplier.get();
        int maxCustomPerChunk = config.spawn().maxCustomMobsPerChunk();
        int current = 0;
        for (Entity entity : location.getChunk().getEntities()) {
            if (!(entity instanceof LivingEntity livingEntity)) {
                continue;
            }
            if (isCustomMob(livingEntity)) {
                current++;
                if (current >= maxCustomPerChunk) {
                    return true;
                }
            }
        }
        return false;
    }

    private void applyEquipment(LivingEntity entity, HexCustomMobsConfig.Equipment equipment) {
        EntityEquipment inventory = entity.getEquipment();
        if (inventory == null) {
            return;
        }

        inventory.setHelmet(toNullableItemStack(equipment.helmet()));
        inventory.setChestplate(toNullableItemStack(equipment.chestplate()));
        inventory.setLeggings(toNullableItemStack(equipment.leggings()));
        inventory.setBoots(toNullableItemStack(equipment.boots()));
        inventory.setItemInMainHand(toNullableItemStack(equipment.mainHand()));
        inventory.setItemInOffHand(toNullableItemStack(equipment.offHand()));

        // Armor and held equipment should never drop - custom drop table only.
        inventory.setHelmetDropChance(0.0F);
        inventory.setChestplateDropChance(0.0F);
        inventory.setLeggingsDropChance(0.0F);
        inventory.setBootsDropChance(0.0F);
        inventory.setItemInMainHandDropChance(0.0F);
        inventory.setItemInOffHandDropChance(0.0F);

    }

    private ItemStack toNullableItemStack(HexCustomMobsConfig.ItemDefinition definition) {
        if (definition == null) {
            return null;
        }
        return toItemStack(definition);
    }

    private ItemStack toItemStack(HexCustomMobsConfig.ItemDefinition definition) {
        ItemStack stack = null;
        if (definition.customItemId() != null) {
            stack = customItemsBridge.createItemById(definition.customItemId(), definition.amount());
        }
        if (stack == null && definition.material() != null) {
            stack = new ItemStack(definition.material(), definition.amount());
        }
        if (stack == null) {
            return null;
        }

        // If custom item source already defines metadata, optional overrides from this config can still apply.
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (definition.name() != null && !definition.name().isBlank()) {
                meta.displayName(LegacyFormat.component(definition.name()));
            }
            if (!definition.lore().isEmpty()) {
                meta.lore(LegacyFormat.components(definition.lore()));
            }
            if (definition.customModelData() != null) {
                meta.setCustomModelData(definition.customModelData());
            }
            stack.setItemMeta(meta);
        }
        stack.setAmount(definition.amount());
        return stack;
    }

    private String healthBar(HexCustomMobsConfig.HpBar hpBar, double currentHealth, double maxHealth) {
        int length = hpBar.barLength();
        double ratio = maxHealth <= 0.0D ? 0.0D : currentHealth / maxHealth;
        ratio = Math.max(0.0D, Math.min(1.0D, ratio));
        int full = (int) Math.round(ratio * length);

        StringBuilder builder = new StringBuilder(length * 4);
        for (int i = 0; i < length; i++) {
            if (i < full) {
                builder.append("&a").append(hpBar.barSymbolFull());
            } else {
                builder.append("&7").append(hpBar.barSymbolEmpty());
            }
        }
        return builder.toString();
    }
}
