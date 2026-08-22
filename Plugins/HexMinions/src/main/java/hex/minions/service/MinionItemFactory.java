package hex.minions.service;

import hex.minions.config.MinionTypeDefinition;
import hex.minions.config.StorageChestDefinition;
import hex.minions.config.TierDefinition;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class MinionItemFactory {
    public static final String ITEM_KIND = "minion";
    public static final String STORAGE_CHEST_KIND = "minion_storage_chest";

    private final Plugin plugin;
    private final NamespacedKey kindKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey tierKey;
    private final NamespacedKey minionIdKey;
    private final NamespacedKey storageChestIdKey;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public MinionItemFactory(Plugin plugin) {
        this.plugin = plugin;
        this.kindKey = new NamespacedKey(plugin, "item_kind");
        this.typeKey = new NamespacedKey(plugin, "minion_type");
        this.tierKey = new NamespacedKey(plugin, "minion_tier");
        this.minionIdKey = new NamespacedKey(plugin, "minion_id");
        this.storageChestIdKey = new NamespacedKey(plugin, "storage_chest_id");
    }

    public ItemStack createMinionItem(MinionTypeDefinition type, int tier, int amount) {
        ItemStack item = type.itemSpec() == null ? new ItemStack(Material.PLAYER_HEAD) : type.itemSpec().toItemStack(plugin);
        if (item == null) item = new ItemStack(Material.PLAYER_HEAD);
        item.setAmount(Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize(replace(type.itemDisplayName(), type, tier)));
            List<net.kyori.adventure.text.Component> lore = type.itemLore().stream()
                    .map(line -> miniMessage.deserialize(replace(line, type, tier)))
                    .toList();
            meta.lore(lore);
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(kindKey, PersistentDataType.STRING, ITEM_KIND);
            pdc.set(typeKey, PersistentDataType.STRING, type.id());
            pdc.set(tierKey, PersistentDataType.INTEGER, tier);
            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemStack createPickupItem(MinionTypeDefinition type, int tier, UUID minionId) {
        ItemStack item = createMinionItem(type, tier, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(minionIdKey, PersistentDataType.STRING, minionId.toString());
            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemStack createStorageChestItem(StorageChestDefinition definition, int amount) {
        ItemStack item = new ItemStack(definition.material(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (definition.customModelData() > 0) meta.setCustomModelData(definition.customModelData());
            meta.displayName(miniMessage.deserialize(definition.displayName()));
            meta.lore(definition.lore().stream().map(line -> miniMessage.deserialize(line)).toList());
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(kindKey, PersistentDataType.STRING, STORAGE_CHEST_KIND);
            pdc.set(storageChestIdKey, PersistentDataType.STRING, definition.id());
            item.setItemMeta(meta);
        }
        return item;
    }

    public Optional<StorageChestItemData> readStorageChestItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!STORAGE_CHEST_KIND.equals(pdc.get(kindKey, PersistentDataType.STRING))) return Optional.empty();
        String id = pdc.get(storageChestIdKey, PersistentDataType.STRING);
        if (id == null || id.isBlank()) return Optional.empty();
        return Optional.of(new StorageChestItemData(id));
    }

    public void markStorageChestBlock(Block block, String storageChestId) {
        if (block == null || storageChestId == null || storageChestId.isBlank()) return;
        if (block.getState() instanceof TileState state) {
            state.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, STORAGE_CHEST_KIND);
            state.getPersistentDataContainer().set(storageChestIdKey, PersistentDataType.STRING, storageChestId);
            state.update(true, false);
        }
    }

    public void unmarkStorageChestBlock(Block block) {
        if (block == null || !(block.getState() instanceof TileState state)) return;
        state.getPersistentDataContainer().remove(kindKey);
        state.getPersistentDataContainer().remove(storageChestIdKey);
        state.update(true, false);
    }

    public Optional<String> readStorageChestBlockId(Block block) {
        if (block == null || !(block.getState() instanceof TileState state)) return Optional.empty();
        PersistentDataContainer pdc = state.getPersistentDataContainer();
        if (!STORAGE_CHEST_KIND.equals(pdc.get(kindKey, PersistentDataType.STRING))) return Optional.empty();
        String id = pdc.get(storageChestIdKey, PersistentDataType.STRING);
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    public Optional<MinionItemData> read(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!ITEM_KIND.equals(pdc.get(kindKey, PersistentDataType.STRING))) return Optional.empty();
        String type = pdc.get(typeKey, PersistentDataType.STRING);
        Integer tier = pdc.get(tierKey, PersistentDataType.INTEGER);
        String id = pdc.get(minionIdKey, PersistentDataType.STRING);
        if (type == null || tier == null) return Optional.empty();
        return Optional.of(new MinionItemData(type, tier, id == null ? null : UUID.fromString(id)));
    }

    public NamespacedKey minionIdKey() {
        return minionIdKey;
    }

    private String replace(String raw, MinionTypeDefinition type, int tier) {
        TierDefinition tierDefinition = type.tier(tier);
        return raw.replace("<tier>", String.valueOf(tier))
                .replace("<max_tier>", String.valueOf(type.maxTier()))
                .replace("<name>", type.displayName())
                .replace("<action_time>", tierDefinition.actionTimeText())
                .replace("<storage_limit>", tierDefinition.storageSlots() + " slotów");
    }

    public record MinionItemData(String typeId, int tier, UUID minionId) {
    }

    public record StorageChestItemData(String id) {
    }
}
