package hexnpc.workflow.menu;

import hexnpc.util.LegacyFormat;
import hexnpc.workflow.ValueResolver;
import hexnpc.workflow.model.WorkflowMenu;
import hexnpc.workflow.model.WorkflowMenuItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class WorkflowMenuRenderer {
    private final ValueResolver resolver;

    public WorkflowMenuRenderer(ValueResolver resolver) {
        this.resolver = resolver;
    }

    public Inventory render(Player player, WorkflowMenu menu) {
        WorkflowMenuHolder holder = new WorkflowMenuHolder(menu.id());
        Inventory inventory = Bukkit.createInventory(holder, menu.size(),
                LegacyFormat.component(resolver.resolveForMenu(menu.title(), player)));
        holder.bind(inventory);
        fillBackground(inventory, menu);
        for (WorkflowMenuItem item : menu.items().values()) inventory.setItem(item.slot(), icon(player, item));
        return inventory;
    }

    private void fillBackground(Inventory inventory, WorkflowMenu menu) {
        ItemStack filler = new ItemStack(menu.background().material());
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            if (menu.background().hideTooltip()) meta.setHideTooltip(true);
            meta.addItemFlags(ItemFlag.values());
            filler.setItemMeta(meta);
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler.clone());
    }

    private ItemStack icon(Player player, WorkflowMenuItem item) {
        ItemStack stack = new ItemStack(item.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (!item.name().isBlank()) meta.displayName(LegacyFormat.component(resolver.resolveForMenu(item.name(), player)));
            if (!item.lore().isEmpty()) {
                List<Component> lore = new ArrayList<>();
                for (String line : item.lore()) lore.add(LegacyFormat.component(resolver.resolveForMenu(line, player)));
                meta.lore(lore);
            }
            if (item.customModelData() != null) meta.setCustomModelData(item.customModelData());
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
