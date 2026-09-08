package hexbuildbattle.item;

import hexbuildbattle.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class ItemBuilder {

    private ItemBuilder() {
    }

    public static ItemStack named(Material material, String name) {
        return named(material, name, List.of());
    }

    public static ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.component(name));
        if (!lore.isEmpty()) {
            meta.lore(lore.stream().map(Text::component).toList());
        }
        item.setItemMeta(meta);
        return item;
    }

    public static Component component(String raw) {
        return Text.component(raw);
    }
}
