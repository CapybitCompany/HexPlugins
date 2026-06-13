package hex.auctionbazaar.auction.gui;

import hex.auctionbazaar.auction.model.AuctionListing;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.config.AuctionConfig;
import hex.auctionbazaar.gui.GuiHolder;
import hex.auctionbazaar.util.ItemSerializer;
import hex.auctionbazaar.util.LegacyFormat;
import hex.auctionbazaar.util.MessageFactory;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Browse-GUI: erste Seite aktiver Listings. Klick = Confirm-Buy-GUI.
 */
public final class AuctionBrowseGui {

    public static void open(Plugin plugin, Player player,
                            Supplier<AuctionConfig> cfg,
                            AuctionService service,
                            EconomyBridge economy,
                            MessageFactory messages) {
        service.listActive(cfg.get().pageSize(), 0).thenAccept(listings ->
                Bukkit.getScheduler().runTask(plugin, () -> render(player, listings, cfg.get(), service, economy, messages))
        );
    }

    private static void render(Player player, List<AuctionListing> listings, AuctionConfig cfg,
                               AuctionService service, EconomyBridge economy, MessageFactory messages) {
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.AUCTION_BROWSE);
        Inventory inv = Bukkit.createInventory(holder, 54, LegacyFormat.component(cfg.guiTitle()));
        holder.bindInventory(inv);

        int slot = 0;
        for (AuctionListing l : listings) {
            if (slot >= cfg.pageSize()) break;
            ItemStack icon = ItemSerializer.deserialize(l.itemBlob());
            if (icon == null) {
                icon = new ItemStack(Material.BARRIER);
            }
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>();
                lore.add(LegacyFormat.component("&7Seller: &f" + (l.sellerName() == null ? "?" : l.sellerName())));
                lore.add(LegacyFormat.component("&7Price: &e" + economy.format(l.price())));
                lore.add(LegacyFormat.component("&7Listing &f#" + l.id()));
                lore.add(Component.empty());
                lore.add(LegacyFormat.component("&aLeft-click to buy"));
                meta.lore(lore);
                icon.setItemMeta(meta);
            }
            inv.setItem(slot, icon);
            final long listingId = l.id();
            final AuctionConfig finalCfg = cfg;
            holder.setSlotAction(slot, ctx -> AuctionConfirmGui.open(
                    Bukkit.getPluginManager().getPlugin("HexAuctionBazaar"), ctx.player(), listingId,
                    () -> finalCfg, service, economy, messages));
            slot++;
        }

        player.openInventory(inv);
    }
}
