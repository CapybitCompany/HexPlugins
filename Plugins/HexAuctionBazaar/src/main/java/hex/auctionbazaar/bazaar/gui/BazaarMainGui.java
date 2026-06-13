package hex.auctionbazaar.bazaar.gui;

import hex.auctionbazaar.bazaar.service.BazaarService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.config.BazaarItemConfig;
import hex.auctionbazaar.gui.GuiHolder;
import hex.auctionbazaar.util.LegacyFormat;
import hex.auctionbazaar.util.MessageFactory;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class BazaarMainGui {

    public static void open(Plugin plugin, Player player, Supplier<BazaarConfig> cfg,
                            BazaarService service, EconomyBridge economy, MessageFactory messages) {
        BazaarConfig c = cfg.get();
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.BAZAAR_MAIN);
        int rows = Math.min(6, Math.max(1, (c.items().size() / 9) + 1));
        Inventory inv = Bukkit.createInventory(holder, rows * 9, LegacyFormat.component(c.guiTitle()));
        holder.bindInventory(inv);

        int slot = 0;
        for (BazaarItemConfig item : c.items().values()) {
            if (slot >= rows * 9) break;
            ItemStack icon = new ItemStack(item.material());
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.displayName(LegacyFormat.component(item.displayName()));
                List<Component> lore = new ArrayList<>();
                lore.add(LegacyFormat.component("&7Base: &e" + economy.format(item.basePrice())));
                lore.add(LegacyFormat.component("&aClick to open buy/sell menu"));
                meta.lore(lore);
                icon.setItemMeta(meta);
            }
            inv.setItem(slot, icon);
            final String key = item.key();
            holder.setSlotAction(slot, ctx -> BazaarItemGui.open(plugin, ctx.player(), key, cfg, service, economy, messages));
            slot++;
        }
        player.openInventory(inv);
    }
}
