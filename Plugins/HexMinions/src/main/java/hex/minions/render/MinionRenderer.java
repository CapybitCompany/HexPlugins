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
import org.bukkit.Chunk;
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
    private final NamespacedKey townUuidKey;
    private final NamespacedKey objectTypeKey;
    private final NamespacedKey objectIdKey;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, Set<UUID>> entityIdsByMinion = new ConcurrentHashMap<>();
    private volatile Definitions definitions;

    public MinionRenderer(Plugin plugin, Definitions definitions) {
        this.plugin = plugin;
        this.definitions = definitions;
        this.minionIdKey = new NamespacedKey(plugin, "minion_id");
        this.partIdKey = new NamespacedKey(plugin, "part_id");
        this.townUuidKey = new NamespacedKey(plugin, "town_uuid");
        this.objectTypeKey = new NamespacedKey(plugin, "object_type");
        this.objectIdKey = new NamespacedKey(plugin, "object_id");
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
        Chunk chunk = world.getChunkAt(minion.location().x() >> 4, minion.location().z() >> 4);
        if (!chunk.isLoaded()) chunk.load(true);
        cleanupExistingParts(chunk, minion.id());
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
        stand.setPersistent(true);
        applyEquipment(stand, appearance, type);
        if (appearance.equipmentLocked()) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                stand.addEquipmentLock(slot, ArmorStand.LockType.ADDING_OR_CHANGING);
                stand.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
            }
        }
        mark(stand, minion, "base");

        TextDisplay label = (TextDisplay) world.spawnEntity(base.clone().add(0, appearance.labelOffsetY(), 0), EntityType.TEXT_DISPLAY);
        label.text(labelText(minion, type));
        label.setBillboard(TextDisplay.Billboard.CENTER);
        label.setPersistent(true);
        mark(label, minion, "label");

        entityIdsByMinion.computeIfAbsent(minion.id(), ignored -> ConcurrentHashMap.newKeySet()).add(stand.getUniqueId());
        entityIdsByMinion.get(minion.id()).add(label.getUniqueId());
    }

    public void updateLabel(MinionInstance minion, MinionTypeDefinition type) {
        updateLabel(minion, type, minion.storageUsed(), minion.storageLimit());
    }

    public void updateLabel(MinionInstance minion, MinionTypeDefinition type, int storageUsed, int storageLimit) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> updateLabel(minion, type, storageUsed, storageLimit));
            return;
        }
        Set<UUID> ids = entityIdsByMinion.get(minion.id());
        if (ids == null) return;
        for (UUID id : ids) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof TextDisplay display) {
                display.text(labelText(minion, type, storageUsed, storageLimit));
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


    private void cleanupExistingParts(Chunk chunk, UUID minionId) {
        if (chunk == null || minionId == null) return;
        String idText = minionId.toString();
        for (Entity entity : chunk.getEntities()) {
            String storedId = entity.getPersistentDataContainer().get(minionIdKey, PersistentDataType.STRING);
            if (idText.equals(storedId)) entity.remove();
        }
        entityIdsByMinion.remove(minionId);
    }

    /** Strict orphan cleanup for a persisted minion that no longer has an ACTIVE town. */
    public void cleanupPersisted(MinionInstance minion) {
        if (minion == null) return;
        World world = Bukkit.getWorld(minion.location().world());
        if (world == null) return;
        Chunk chunk = world.getChunkAt(minion.location().x() >> 4, minion.location().z() >> 4);
        if (!chunk.isLoaded()) chunk.load(true);
        cleanupExistingParts(chunk, minion.id());
    }

    private void mark(Entity entity, MinionInstance minion, String partId) {
        entity.getPersistentDataContainer().set(minionIdKey, PersistentDataType.STRING, minion.id().toString());
        entity.getPersistentDataContainer().set(partIdKey, PersistentDataType.STRING, partId);
        entity.getPersistentDataContainer().set(townUuidKey, PersistentDataType.STRING, minion.townUuid().toString());
        entity.getPersistentDataContainer().set(objectTypeKey, PersistentDataType.STRING, "minion_visual");
        entity.getPersistentDataContainer().set(objectIdKey, PersistentDataType.STRING, minion.id() + ":" + partId);
    }

    private net.kyori.adventure.text.Component labelText(MinionInstance minion, MinionTypeDefinition type) {
        return labelText(minion, type, minion.storageUsed(), minion.storageLimit());
    }

    private net.kyori.adventure.text.Component labelText(MinionInstance minion, MinionTypeDefinition type, int storageUsed, int storageLimit) {
        int percent = storageLimit <= 0 ? 0 : (int) Math.min(100, Math.round(storageUsed * 100.0 / storageLimit));
        String tierLabel = tierLabel(minion.tier());
        String tierNumber = tierNumber(minion.tier());
        String text = definitions.appearance(type.appearanceId()).labelText()
                .replace("<name>", type.displayName())
                .replace("Tier <tier>", tierLabel)
                .replace("<tier_label>", tierLabel)
                .replace("<tier>", tierNumber)
                .replace("<storage_used>", String.valueOf(storageUsed))
                .replace("<storage_limit>", String.valueOf(storageLimit))
                .replace("<storage_percent>", String.valueOf(percent))
                .replace("<storage_bar>", "<gray>Magazyn: <white>" + percent + "%</white></gray>");
        return miniMessage.deserialize(text);
    }

    private String tierLabel(int tier) {
        return tierColor(tier) + "Tier " + tier + "</" + tierColorName(tier) + ">";
    }

    private String tierNumber(int tier) {
        return tierColor(tier) + tier + "</" + tierColorName(tier) + ">";
    }

    private String tierColor(int tier) {
        return switch (tier) {
            case 1 -> "<gray>";
            case 2 -> "<white>";
            case 3 -> "<aqua>";
            case 4 -> "<dark_green>";
            case 5 -> "<green>";
            case 6 -> "<gold>";
            case 7 -> "<dark_red>";
            default -> "<gray>";
        };
    }

    private String tierColorName(int tier) {
        return switch (tier) {
            case 1 -> "gray";
            case 2 -> "white";
            case 3 -> "aqua";
            case 4 -> "dark_green";
            case 5 -> "green";
            case 6 -> "gold";
            case 7 -> "dark_red";
            default -> "gray";
        };
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

