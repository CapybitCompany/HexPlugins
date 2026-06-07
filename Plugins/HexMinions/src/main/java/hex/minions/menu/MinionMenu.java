package hex.minions.menu;

import hex.core.api.HexApi;
import hex.minions.api.MinionMenuData;
import hex.minions.config.MinionTypeDefinition;
import hex.minions.config.ResourceDefinition;
import hex.minions.config.TierDefinition;
import hex.minions.service.MinionService;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MinionMenu {
    private static final int[] STORAGE_SLOTS = {19, 20, 21, 22, 23, 24, 25, 30, 31};

    private final HexApi hex;
    private final MinionService service;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public MinionMenu(HexApi hex, MinionService service) {
        this.hex = hex;
        this.service = service;
    }

    public void open(Player player, UUID minionId) {
        Optional<MinionMenuData> data = service.minionData(player, minionId);
        if (data.isEmpty()) {
            hex.ui().send(player, "minions.error.not-found");
            return;
        }
        MinionMenuData d = data.get();
        service.select(player, minionId);
        Inventory inv = Bukkit.createInventory(new MinionMenuHolder(minionId), 54, miniMessage.deserialize("<dark_gray>Minion: " + d.displayName()));
        fill(inv);
        inv.setItem(4, item(Material.PLAYER_HEAD, d.displayName(), List.of(
                "<gray>Tier: <white>" + d.tier() + "/" + d.maxTier(),
                "<gray>Storage: <white>" + d.storageUsed() + "/" + d.storageLimit(),
                "<gray>Lokacja: <white>" + d.world() + " " + d.x() + "," + d.y() + "," + d.z()
        )));
        renderStorage(inv, d);
        inv.setItem(37, item(Material.LAVA_BUCKET, "<gold>Slot boostera</gold>", List.of(
                "<gray>Tutaj będzie widoczny booster miniona.</gray>",
                "<dark_gray>Przykład: Enchanted Lava Bucket z upgrades.yml.</dark_gray>"
        )));
        inv.setItem(43, item(Material.HOPPER, "<green>Slot ulepszenia</green>", List.of(
                "<gray>Tutaj będzie widoczny upgrade miniona.</gray>",
                "<dark_gray>Przykład: Compactor z upgrades.yml.</dark_gray>"
        )));
        inv.setItem(45, item(Material.ENDER_PEARL, "<aqua>Przenieś tutaj</aqua>", List.of("<gray>Przenieś do pozycji, w której stoisz.</gray>")));
        inv.setItem(48, item(Material.CHEST, "<green>Odbierz surowce</green>", List.of("<gray>Przenieś storage do ekwipunku.</gray>")));
        inv.setItem(50, item(Material.ANVIL, "<gold>Ulepsz</gold>", List.of("<gray>Wymagania: <white>" + d.nextUpgradeRequirementsText())));
        inv.setItem(53, item(Material.BARRIER, "<red>Podnieś miniona</red>", List.of("<gray>Zwraca item miniona.</gray>")));
        player.openInventory(inv);
    }

    private void fill(Inventory inv) {
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    private void renderStorage(Inventory inv, MinionMenuData data) {
        List<ItemStack> stacks = storageStacks(data.storage());
        for (int i = 0; i < STORAGE_SLOTS.length; i++) {
            int slot = STORAGE_SLOTS[i];
            int storageSlot = i + 1;
            if (storageSlot > data.storageSlotsUnlocked()) {
                inv.setItem(slot, item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>Zablokowany slot storage</gray>", List.of(
                        "<gray>Odblokowanie: <white>Tier " + unlockTier(data.typeId(), storageSlot) + "</white></gray>",
                        "<dark_gray>Konfiguracja: minion-types.yml → tiers → storage-slots</dark_gray>"
                )));
                continue;
            }
            inv.setItem(slot, i < stacks.size() ? stacks.get(i) : item(Material.GRAY_STAINED_GLASS_PANE, "<gray>Pusty slot storage</gray>", List.of("<dark_gray>Minion nie ma tu jeszcze surowca.</dark_gray>")));
        }
    }

    private List<ItemStack> storageStacks(Map<String, Long> storage) {
        java.util.ArrayList<ItemStack> result = new java.util.ArrayList<>();
        for (Map.Entry<String, Long> entry : storage.entrySet()) {
            ResourceDefinition def = service.definitions().resources().get(entry.getKey());
            Material material = def == null ? Material.CHEST : def.material();
            String name = def == null ? entry.getKey() : def.displayName();
            long remaining = entry.getValue();
            int stackSize = def == null ? 64 : def.stackSize();
            while (remaining > 0 && result.size() < STORAGE_SLOTS.length) {
                int amount = (int) Math.max(1, Math.min(Math.min(64, stackSize), remaining));
                result.add(item(material, name + " <gray>x" + amount + "</gray>", List.of("<gray>Ilość w storage: <white>" + entry.getValue() + "</white></gray>"), amount, def == null ? 0 : def.customModelData()));
                remaining -= amount;
            }
        }
        return result;
    }

    private int unlockTier(String typeId, int storageSlot) {
        MinionTypeDefinition type = service.definitions().minionTypes().get(typeId);
        if (type == null) return storageSlot;
        return type.tiers().values().stream()
                .filter(tier -> tier.storageSlots() >= storageSlot)
                .mapToInt(TierDefinition::tier)
                .min()
                .orElse(type.maxTier());
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        return item(material, name, lore, 1, 0);
    }

    private ItemStack item(Material material, String name, List<String> lore, int amount, int customModelData) {
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (customModelData > 0) meta.setCustomModelData(customModelData);
            meta.displayName(miniMessage.deserialize(name));
            meta.lore(lore.stream().map(miniMessage::deserialize).toList());
            item.setItemMeta(meta);
        }
        return item;
    }
}

