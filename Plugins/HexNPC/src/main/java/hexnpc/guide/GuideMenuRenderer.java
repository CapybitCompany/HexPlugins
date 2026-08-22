package hexnpc.guide;

import hexnpc.guide.model.GuideEntry;
import hexnpc.guide.model.GuideIcon;
import hexnpc.guide.model.GuideMenu;
import hexnpc.util.LegacyFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GuideMenuRenderer {

    public Inventory render(GuideMenu menu) {
        Map<Integer, String> targets = new LinkedHashMap<>();
        for (GuideEntry entry : menu.entries().values()) {
            if (entry.navigates()) targets.put(entry.slot(), entry.target());
        }
        GuideMenuHolder holder = new GuideMenuHolder(menu.id(), menu.parent(), menu.backSlot(), targets);
        Inventory inventory = Bukkit.createInventory(holder, menu.size(), LegacyFormat.component(menu.title()));
        holder.bind(inventory);
        fillBackground(inventory, menu);
        for (GuideEntry entry : menu.entries().values()) {
            inventory.setItem(entry.slot(), icon(entry.icon()));
        }
        if (menu.hasParent()) {
            inventory.setItem(menu.backSlot(), named(Material.RED_WOOL, "&cCofnij", List.of("&7Wróć do poprzedniego menu.")));
        }
        return inventory;
    }

    private void fillBackground(Inventory inventory, GuideMenu menu) {
        ItemStack filler = new ItemStack(menu.background().material());
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            if (menu.background().hideTooltip()) meta.setHideTooltip(true);
            meta.addItemFlags(ItemFlag.values());
            filler.setItemMeta(meta);
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler.clone());
        }
    }

    private ItemStack icon(GuideIcon icon) {
        ItemStack stack = new ItemStack(icon.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (!icon.name().isBlank()) meta.displayName(LegacyFormat.component(icon.name()));
            if (!icon.lore().isEmpty()) {
                List<Component> lore = new ArrayList<>(icon.lore().size());
                for (String line : icon.lore()) lore.add(LegacyFormat.component(line));
                meta.lore(lore);
            }
            if (icon.customModelData() != null) meta.setCustomModelData(icon.customModelData());
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack named(Material material, String name, List<String> loreLines) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyFormat.component(name));
            if (loreLines != null && !loreLines.isEmpty()) {
                List<Component> lore = new ArrayList<>();
                for (String line : loreLines) lore.add(LegacyFormat.component(line));
                meta.lore(lore);
            }
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
