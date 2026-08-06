package hex.auctionbazaar.auction.gui;

import hex.auctionbazaar.auction.model.AuctionListing;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.config.AuctionConfig;
import hex.auctionbazaar.gui.GuiFrame;
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
import java.util.Locale;
import java.util.function.Supplier;

import static hex.auctionbazaar.util.MessageFactory.placeholders;

/**
 * GUI listy własnych aukcji gracza - z prawdziwą paginacją (LIMIT/OFFSET po
 * liczbie konfigurowalnych item-slotów), przyciskiem „Wróć” (BARRIER) i
 * NIEZMIENNYM snapshotem podatkowym (brutto/podatek/netto z aukcji, bez
 * przeliczania wg aktualnych permisji).
 */
public final class AuctionMyListingsGui {

    public static void open(Plugin plugin, Player player, Supplier<AuctionConfig> cfg,
                            AuctionService service, EconomyBridge economy, MessageFactory messages) {
        open(plugin, player, cfg, service, economy, messages, 0, 0, AuctionService.SortMode.NEWEST);
    }

    public static void open(Plugin plugin, Player player, Supplier<AuctionConfig> cfg,
                            AuctionService service, EconomyBridge economy, MessageFactory messages,
                            int page, int browsePage, AuctionService.SortMode browseSort) {
        int capacity = cfg.get().itemSlots().size();
        int safePage = Math.max(0, page);
        int offset = AuctionItemArea.offset(safePage, capacity);
        service.listMine(player.getUniqueId(), capacity, offset).thenAcceptBoth(
                service.countMine(player.getUniqueId()),
                (list, total) -> Bukkit.getScheduler().runTask(plugin, () -> render(plugin, player, list,
                        total, cfg.get(), service, economy, messages, safePage, browsePage, browseSort)));
    }

    private static void render(Plugin plugin, Player player, List<AuctionListing> list, int total,
                               AuctionConfig cfg, AuctionService service, EconomyBridge economy,
                               MessageFactory messages, int page, int browsePage,
                               AuctionService.SortMode browseSort) {
        List<Integer> itemSlots = cfg.itemSlots();
        int capacity = itemSlots.size();
        int totalPages = AuctionItemArea.totalPages(total, capacity);
        if (page > totalPages - 1) {
            open(plugin, player, () -> cfg, service, economy, messages, totalPages - 1, browsePage, browseSort);
            return;
        }

        GuiHolder holder = new GuiHolder(GuiHolder.Kind.AUCTION_MY_LISTINGS);
        Inventory inv = Bukkit.createInventory(holder, 54, LegacyFormat.component(cfg.myListingsTitle()));
        holder.bindInventory(inv);

        for (int i = 0; i < list.size() && i < capacity; i++) {
            AuctionListing l = list.get(i);
            int slot = itemSlots.get(i);
            ItemStack icon = ItemSerializer.deserialize(l.itemBlob());
            if (icon == null) icon = new ItemStack(Material.BARRIER);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>();
                lore.add(LegacyFormat.component(messages.raw("auction.gui.mine-id",
                        placeholders("id", String.valueOf(l.id())))));
                lore.add(LegacyFormat.component(messages.raw("auction.gui.mine-status",
                        placeholders("status", statusLabel(messages, l)))));
                lore.add(LegacyFormat.component(messages.raw("auction.gui.mine-price",
                        placeholders("price", economy.format(l.price())))));
                lore.add(LegacyFormat.component(messages.raw("auction.gui.mine-tax",
                        placeholders("percent", l.taxPercentOrZero().toPlainString(),
                                "tax", economy.format(l.taxAmountOrZero())))));
                lore.add(LegacyFormat.component(messages.raw("auction.gui.mine-net",
                        placeholders("net", economy.format(l.economicNetOrGross())))));
                lore.add(Component.empty());
                lore.add(LegacyFormat.component(messages.raw("auction.gui.mine-cancel-hint", null)));
                meta.lore(lore);
                icon.setItemMeta(meta);
            }
            inv.setItem(slot, icon);
            final long listingId = l.id();
            final int curPage = page;
            holder.setSlotAction(slot, ctx -> {
                if (!ctx.isRight()) return;
                service.cancel(ctx.player(), listingId).thenAccept(outcome ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            switch (outcome) {
                                case OK -> messages.send(ctx.player(), "auction.listing-cancelled",
                                        placeholders("id", String.valueOf(listingId)));
                                case NOT_OWNER -> messages.send(ctx.player(), "auction.listing-not-yours");
                                case NOT_ACTIVE -> messages.send(ctx.player(), "auction.listing-not-active");
                                case NO_PERMISSION -> messages.send(ctx.player(), "common.no-permission");
                                case NOT_FOUND -> messages.send(ctx.player(), "auction.listing-not-found",
                                        placeholders("id", String.valueOf(listingId)));
                            }
                            open(plugin, ctx.player(), () -> cfg, service, economy, messages,
                                    curPage, browsePage, browseSort);
                        }));
            });
        }

        if (total == 0) {
            inv.setItem(itemSlots.get(itemSlots.size() / 2), GuiFrame.button(Material.BARRIER,
                    messages.raw("auction.gui.mine-empty-title", null),
                    List.of(messages.raw("auction.gui.mine-empty-lore-1", null),
                            messages.raw("auction.gui.mine-empty-lore-2", null))));
        }

        AuctionPagedControls.render(inv, holder, cfg, messages, page, totalPages, total,
                "auction.gui.mine-page-info",
                p -> open(plugin, player, () -> cfg, service, economy, messages, p, browsePage, browseSort),
                () -> AuctionBrowseGui.open(plugin, player, () -> cfg, service, economy, messages,
                        browsePage, browseSort));

        GuiFrame.fillEmpty(inv, GuiFrame.materialOrDefault(cfg.frameMaterial(), Material.BLACK_STAINED_GLASS_PANE));
        player.openInventory(inv);
    }

    private static String statusLabel(MessageFactory messages, AuctionListing l) {
        String key = "auction.status." + l.state().name().toLowerCase(Locale.ROOT);
        String label = messages.raw(key, null);
        return label.startsWith("&cmissing") ? l.state().name() : label;
    }
}
