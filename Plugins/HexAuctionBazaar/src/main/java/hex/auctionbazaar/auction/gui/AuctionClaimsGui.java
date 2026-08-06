package hex.auctionbazaar.auction.gui;

import hex.auctionbazaar.HexAuctionBazaarPlugin;
import hex.auctionbazaar.auction.model.AuctionClaim;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.config.AuctionConfig;
import hex.auctionbazaar.gui.GuiFrame;
import hex.auctionbazaar.gui.GuiHolder;
import hex.auctionbazaar.util.ClaimReasonTranslator;
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
 * GUI odbioru przedmiotów (claims) - z prawdziwą paginacją (LIMIT/OFFSET) oraz
 * przyciskiem „Wróć” (BARRIER) do Domu Aukcyjnego. Przyjazne tłumaczenie
 * technicznego powodu claim-u pochodzi z messages.yml (claim-reasons).
 */
public final class AuctionClaimsGui {

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
        service.listClaims(player.getUniqueId(), capacity, offset).thenAcceptBoth(
                service.countClaims(player.getUniqueId()),
                (list, total) -> Bukkit.getScheduler().runTask(plugin, () -> render(plugin, player, list,
                        total, cfg.get(), service, economy, messages, safePage, browsePage, browseSort)));
    }

    private static void render(Plugin plugin, Player player, List<AuctionClaim> list, int total,
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

        GuiHolder holder = new GuiHolder(GuiHolder.Kind.AUCTION_CLAIMS);
        Inventory inv = Bukkit.createInventory(holder, 54, LegacyFormat.component(cfg.claimsTitle()));
        holder.bindInventory(inv);

        HexAuctionBazaarPlugin main = plugin instanceof HexAuctionBazaarPlugin p ? p : null;
        ClaimReasonTranslator translator = main == null ? null
                : new ClaimReasonTranslator(() -> main.config().messages());

        for (int i = 0; i < list.size() && i < capacity; i++) {
            AuctionClaim c = list.get(i);
            int slot = itemSlots.get(i);
            String friendlyReason = translator == null ? c.reason() : translator.friendly(c.reason());
            ItemStack icon;
            if (c.isMoney()) {
                icon = new ItemStack(Material.GOLD_INGOT);
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    meta.displayName(LegacyFormat.component(messages.raw("auction.gui.claim-money-name",
                            MessageFactory.placeholders("amount", economy.format(c.moneyAmount())))));
                    meta.lore(claimLore(messages, friendlyReason));
                    icon.setItemMeta(meta);
                }
            } else {
                ItemStack item = ItemSerializer.deserialize(c.itemBlob());
                if (item == null) item = new ItemStack(Material.BARRIER);
                icon = item;
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    meta.lore(claimLore(messages, friendlyReason));
                    icon.setItemMeta(meta);
                }
            }
            inv.setItem(slot, icon);
            final long claimId = c.id();
            final AuctionClaim claim = c;
            final int curPage = page;
            holder.setSlotAction(slot, ctx -> service.consumeClaim(ctx.player(), claimId)
                    .thenAccept(outcome -> Bukkit.getScheduler().runTask(plugin, () -> {
                        switch (outcome) {
                            case OK -> {
                                messages.send(ctx.player(), "auction.claim-received",
                                        MessageFactory.placeholders("what", claim.isMoney()
                                                ? economy.format(claim.moneyAmount())
                                                : messages.raw("auction.gui.claim-item-collected", null)));
                                open(plugin, ctx.player(), () -> cfg, service, economy, messages,
                                        curPage, browsePage, browseSort);
                            }
                            case INVENTORY_FULL -> messages.send(ctx.player(), "auction.inventory-full");
                            case COMPENSATION_FAILED ->
                                    messages.send(ctx.player(), "auction.compensation-failed");
                            case NOT_AVAILABLE -> messages.send(ctx.player(), "auction.no-claims");
                            case ECONOMY_FAILED -> messages.send(ctx.player(), "common.economy-error");
                            case DB_FAILED -> messages.send(ctx.player(), "common.db-error");
                        }
                    })));
        }

        if (total == 0) {
            inv.setItem(itemSlots.get(itemSlots.size() / 2), GuiFrame.button(Material.BARRIER,
                    messages.raw("auction.gui.claims-empty-title", null),
                    List.of(messages.raw("auction.gui.claims-empty-lore-1", null))));
        }

        AuctionPagedControls.render(inv, holder, cfg, messages, page, totalPages, total,
                "auction.gui.claims-page-info",
                p -> open(plugin, player, () -> cfg, service, economy, messages, p, browsePage, browseSort),
                () -> AuctionBrowseGui.open(plugin, player, () -> cfg, service, economy, messages,
                        browsePage, browseSort));

        GuiFrame.fillEmpty(inv, GuiFrame.materialOrDefault(cfg.frameMaterial(), Material.BLACK_STAINED_GLASS_PANE));
        player.openInventory(inv);
    }

    private static List<Component> claimLore(MessageFactory messages, String friendlyReason) {
        List<Component> lore = new ArrayList<>();
        lore.add(LegacyFormat.component(messages.raw("auction.gui.claim-reason",
                MessageFactory.placeholders("reason", friendlyReason))));
        lore.add(LegacyFormat.component(messages.raw("auction.gui.claim-collect-hint", null)));
        return lore;
    }
}
