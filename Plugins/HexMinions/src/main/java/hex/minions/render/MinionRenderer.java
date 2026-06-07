package hex.minions.render;

import hex.minions.config.AppearanceDefinition;
import hex.minions.config.Definitions;
import hex.minions.config.ItemSpec;
import hex.minions.config.MinionTypeDefinition;
import hex.minions.model.MinionInstance;
import hex.minions.model.MinionLocation;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MinionRenderer {
    private final Plugin plugin;
    private final NamespacedKey minionIdKey;
    private final NamespacedKey partIdKey;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Set<UUID>> entityIdsByMinion = new ConcurrentHashMap<>();
    private volatile Definitions definitions;

    public MinionRenderer(Plugin plugin, Definitions definitions) {
        this.plugin = plugin;
        this.definitions = definitions;
        this.minionIdKey = new NamespacedKey(plugin, "minion_id");
        this.partIdKey = new NamespacedKey(plugin, "part_id");
    }

    public NamespacedKey minionIdKey() { return minionIdKey; }
    public NamespacedKey partIdKey() { return partIdKey; }

    public void reload(Definitions definitions) {
        this.definitions = definitions;
    }

    public void spawn(MinionInstance minion, MinionTypeDefinition type) {
        despawn(minion.id());
        World world = Bukkit.getWorld(minion.location().world());
        if (world == null) return;
        AppearanceDefinition appearance = definitions.appearance(type.appearanceId());
        Location base = toBukkit(minion.location()).add(0.5, 0.0, 0.5);
        ArmorStand stand = (ArmorStand) world.spawnEntity(base, EntityType.ARMOR_STAND);
        stand.setSmall(appearance.small());
        stand.setVisible(!appearance.invisible());
        stand.setInvulnerable(appearance.invulnerable());
        stand.setGravity(!appearance.noGravity());
        stand.setArms(appearance.arms());
        stand.setMarker(appearance.marker());
        stand.setCanPickupItems(false);
        stand.setPersistent(false);
        applyEquipment(stand, appearance, type);
        if (appearance.equipmentLocked()) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                stand.addEquipmentLock(slot, ArmorStand.LockType.ADDING_OR_CHANGING);
                stand.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
            }
        }
        mark(stand, minion.id(), "base");

        TextDisplay label = (TextDisplay) world.spawnEntity(base.clone().add(0, appearance.labelOffsetY(), 0), EntityType.TEXT_DISPLAY);
        label.text(labelText(minion, type));
        label.setBillboard(TextDisplay.Billboard.CENTER);
        label.setPersistent(false);
        mark(label, minion.id(), "label");

        entityIdsByMinion.computeIfAbsent(minion.id(), ignored -> ConcurrentHashMap.newKeySet()).add(stand.getUniqueId());
        entityIdsByMinion.get(minion.id()).add(label.getUniqueId());
    }

    public void updateLabel(MinionInstance minion, MinionTypeDefinition type) {
        Set<UUID> ids = entityIdsByMinion.get(minion.id());
        if (ids == null) return;
        for (UUID id : ids) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof TextDisplay display) {
                display.text(labelText(minion, type));
            }
        }
    }

    public void despawn(UUID minionId) {
        Set<UUID> ids = entityIdsByMinion.remove(minionId);
        if (ids == null) return;
        for (UUID id : ids) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
    }

    public void shutdown() {
        for (UUID id : Set.copyOf(entityIdsByMinion.keySet())) {
            despawn(id);
        }
    }

    private void mark(Entity entity, UUID minionId, String partId) {
        entity.getPersistentDataContainer().set(minionIdKey, PersistentDataType.STRING, minionId.toString());
        entity.getPersistentDataContainer().set(partIdKey, PersistentDataType.STRING, partId);
    }

    private net.kyori.adventure.text.Component labelText(MinionInstance minion, MinionTypeDefinition type) {
        int percent = minion.storageLimit() <= 0 ? 0 : (int) Math.min(100, Math.round(minion.storageUsed() * 100.0 / minion.storageLimit()));
        String text = definitions.appearance(type.appearanceId()).labelText()
                .replace("<name>", type.displayName())
                .replace("<tier>", String.valueOf(minion.tier()))
                .replace("<storage_used>", String.valueOf(minion.storageUsed()))
                .replace("<storage_limit>", String.valueOf(minion.storageLimit()))
                .replace("<storage_percent>", String.valueOf(percent))
                .replace("<storage_bar>", "<gray>Storage: <white>" + percent + "%</white></gray>");
        return miniMessage.deserialize(text);
    }

    private void applyEquipment(ArmorStand stand, AppearanceDefinition appearance, MinionTypeDefinition type) {
        ItemStack fallbackHelmet = new ItemStack(type.itemMaterial());
        stand.getEquipment().setHelmet(stack(appearance.helmet(), fallbackHelmet));
        stand.getEquipment().setChestplate(stack(appearance.chestplate(), null));
        stand.getEquipment().setLeggings(stack(appearance.leggings(), null));
        stand.getEquipment().setBoots(stack(appearance.boots(), null));
        stand.getEquipment().setItemInMainHand(stack(appearance.mainHand(), null));
        stand.getEquipment().setItemInOffHand(stack(appearance.offHand(), null));
    }

    private ItemStack stack(ItemSpec spec, ItemStack fallback) {
        ItemStack item = spec == null ? null : spec.toItemStack(plugin);
        return item == null ? fallback : item;
    }

    private Location toBukkit(MinionLocation location) {
        World world = Bukkit.getWorld(location.world());
        return new Location(world, location.x(), location.y(), location.z(), location.yaw(), 0);
    }
}

