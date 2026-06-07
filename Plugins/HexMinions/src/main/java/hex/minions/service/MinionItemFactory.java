package hex.minions.service;

import hex.minions.config.MinionTypeDefinition;
import hex.minions.config.TierDefinition;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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

    private final Plugin plugin;
    private final NamespacedKey kindKey;
    private final NamespacedKey typeKey;
    private final NamespacedKey tierKey;
    private final NamespacedKey minionIdKey;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public MinionItemFactory(Plugin plugin) {
        this.plugin = plugin;
        this.kindKey = new NamespacedKey(plugin, "item_kind");
        this.typeKey = new NamespacedKey(plugin, "minion_type");
        this.tierKey = new NamespacedKey(plugin, "minion_tier");
        this.minionIdKey = new NamespacedKey(plugin, "minion_id");
    }

    public ItemStack createMinionItem(MinionTypeDefinition type, int tier, int amount) {
        ItemStack item = new ItemStack(type.itemMaterial() == null ? Material.PLAYER_HEAD : type.itemMaterial(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (type.itemCustomModelData() > 0) {
                meta.setCustomModelData(type.itemCustomModelData());
            }
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
                .replace("<action_time>", String.valueOf(tierDefinition.actionTimeSeconds()))
                .replace("<storage_limit>", String.valueOf(tierDefinition.storage()));
    }

    public record MinionItemData(String typeId, int tier, UUID minionId) {
    }
}

