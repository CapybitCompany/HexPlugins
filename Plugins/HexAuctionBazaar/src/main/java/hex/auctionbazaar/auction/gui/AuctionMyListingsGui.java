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

import static hex.auctionbazaar.util.MessageFactory.placeholders;

/**
 * GUI listy wlasnych aukcji gracza. Wszystkie widoczne stringi
 * pochodza z messages.yml aby zachowac Polski jezyk.
 */
public final class AuctionMyListingsGui {

    public static void open(Plugin plugin, Player player, Supplier<AuctionConfig> cfg,
                            AuctionService service, EconomyBridge economy, MessageFactory messages) {
        service.listMine(player.getUniqueId()).thenAccept(list ->
                Bukkit.getScheduler().runTask(plugin, () -> render(plugin, player, list, cfg.get(), service, economy, messages))
        );
    }

    private static void render(Plugin plugin, Player player, List<AuctionListing> list, AuctionConfig cfg,
                               AuctionService service, EconomyBridge economy, MessageFactory messages) {
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.AUCTION_MY_LISTINGS);
        Inventory inv = Bukkit.createInventory(holder, 54, LegacyFormat.component(cfg.myListingsTitle()));
        holder.bindInventory(inv);

        int slot = 0;
        for (AuctionListing l : list) {
            if (slot >= cfg.pageSize()) break;
            ItemStack icon = ItemSerializer.deserialize(l.itemBlob());
            if (icon == null) icon = new ItemStack(Material.BARRIER);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>();
                lore.add(LegacyFormat.component(messages.raw("auction.gui.mine-id",
                        placeholders("id", String.valueOf(l.id())))));
                lore.add(LegacyFormat.component(messages.raw("auction.gui.mine-status",
                        placeholders("status", l.state().name()))));
                lore.add(LegacyFormat.component(messages.raw("auction.gui.mine-price",
                        placeholders("price", economy.format(l.price())))));
                lore.add(Component.empty());
                lore.add(LegacyFormat.component(messages.raw("auction.gui.mine-cancel-hint", null)));
                meta.lore(lore);
                icon.setItemMeta(meta);
            }
            inv.setItem(slot, icon);
            final long listingId = l.id();
            holder.setSlotAction(slot, ctx -> {
                if (!ctx.isRight()) return;
                ctx.player().closeInventory();
                service.cancel(ctx.player(), listingId).thenAccept(outcome ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            switch (outcome) {
                                case OK -> messages.send(ctx.player(), "auction.listing-cancelled",
                                        MessageFactory.placeholders("id", String.valueOf(listingId)));
                                case NOT_OWNER -> messages.send(ctx.player(), "auction.listing-not-yours");
                                case NOT_ACTIVE -> messages.send(ctx.player(), "auction.listing-not-active");
                                case NOT_FOUND -> messages.send(ctx.player(), "auction.listing-not-found",
                                        MessageFactory.placeholders("id", String.valueOf(listingId)));
                            }
                        }));
            });
            slot++;
        }
        player.openInventory(inv);
    }
}
