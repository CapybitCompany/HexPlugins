package hex.towns.service;

import hex.towns.api.TownBoundItems;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Central ownership marker for reusable progression assets. The keys deliberately
 * live in the HexTowns namespace so dependent plugins share one provenance model.
 */
public final class TownBoundItemService implements TownBoundItems {
    private static final int CURRENT_VERSION = 1;

    private final NamespacedKey originTownKey;
    private final NamespacedKey versionKey;
    private final NamespacedKey kindKey;
    private final Predicate<UUID> activeTown;

    public TownBoundItemService(Plugin plugin, Predicate<UUID> activeTown) {
        this.originTownKey = new NamespacedKey(plugin, "origin_town_uuid");
        this.versionKey = new NamespacedKey(plugin, "town_bound_version");
        this.kindKey = new NamespacedKey(plugin, "town_bound_kind");
        this.activeTown = activeTown;
    }

    @Override
    public boolean isTownBound(ItemStack item) {
        return originTown(item).isPresent();
    }

    @Override
    public Optional<UUID> originTown(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
        String raw = item.getItemMeta().getPersistentDataContainer().get(originTownKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> kind(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
        String raw = item.getItemMeta().getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw);
    }

    @Override
    public ItemStack bind(ItemStack item, UUID townUuid, String kind) {
        if (item == null || item.getType().isAir() || townUuid == null) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(originTownKey, PersistentDataType.STRING, townUuid.toString());
        pdc.set(versionKey, PersistentDataType.INTEGER, CURRENT_VERSION);
        if (kind != null && !kind.isBlank()) pdc.set(kindKey, PersistentDataType.STRING, kind);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public boolean canUse(ItemStack item, UUID currentTownUuid) {
        Optional<UUID> origin = originTown(item);
        if (origin.isEmpty()) return true; // legacy item; caller decides whether to adopt it.
        return currentTownUuid != null && origin.get().equals(currentTownUuid) && activeTown.test(origin.get());
    }

    @Override
    public boolean validateOrAdopt(ItemStack item, UUID currentTownUuid, String kind) {
        if (item == null || item.getType().isAir() || currentTownUuid == null || !activeTown.test(currentTownUuid)) return false;
        Optional<UUID> origin = originTown(item);
        if (origin.isEmpty()) {
            bind(item, currentTownUuid, kind);
            return true;
        }
        return origin.get().equals(currentTownUuid) && activeTown.test(origin.get());
    }

    @Override
    public boolean belongsToDeadTown(ItemStack item) {
        Optional<UUID> origin = originTown(item);
        return origin.isPresent() && !activeTown.test(origin.get());
    }

    @Override
    public int purgeTownBound(Inventory inventory, UUID townUuid) {
        if (inventory == null || townUuid == null) return 0;
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            if (originTown(item).filter(townUuid::equals).isPresent()) {
                inventory.setItem(slot, null);
                removed += Math.max(1, item.getAmount());
            }
        }
        return removed;
    }

    public NamespacedKey originTownKey() { return originTownKey; }
    public NamespacedKey versionKey() { return versionKey; }
    public NamespacedKey kindKey() { return kindKey; }
}
