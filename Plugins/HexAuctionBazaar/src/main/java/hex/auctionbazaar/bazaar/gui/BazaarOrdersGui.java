package hex.auctionbazaar.bazaar.gui;

import hex.auctionbazaar.HexAuctionBazaarPlugin;
import hex.auctionbazaar.bazaar.model.BazaarOrder;
import hex.auctionbazaar.bazaar.model.OrderSide;
import hex.auctionbazaar.bazaar.model.OrderState;
import hex.auctionbazaar.bazaar.service.BazaarService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.gui.GuiFrame;
import hex.auctionbazaar.gui.GuiHolder;
import hex.auctionbazaar.util.LegacyFormat;
import hex.auctionbazaar.util.MessageFactory;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static hex.auctionbazaar.util.MessageFactory.placeholders;

/**
 * GUI zarządzania zleceniami gracza (Bazaar Order Book).
 *  - Otwarte zlecenia: PPM anuluje (odzyskujesz środki/przedmioty).
 *  - Anulowane zlecenia (historia): PPM usuwa widoczny wpis - bez ruszania
 *    istniejących claim-ów/zwrotów (punkt #5).
 * Ikony: PAPER = BUY-order (kupno), WRITABLE_BOOK = SELL-offer (oferta).
 */
public final class BazaarOrdersGui {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int PAGE_SIZE = 45;          // sloty 0..44 na zlecenia
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREV = 47;
    private static final int SLOT_INFO = 49;          // info o stronie (klik = odśwież)
    private static final int SLOT_NEXT = 51;
    private static final int SLOT_CLOSE = 53;
    private static final int SLOT_EMPTY_STATE = 22;

    public static void open(Plugin plugin, Player player, Supplier<BazaarConfig> cfg,
                            BazaarService service, EconomyBridge economy, MessageFactory messages) {
        open(plugin, player, cfg, service, economy, messages, 0);
    }

    /** Stronicowane otwarcie: DB-side LIMIT/OFFSET + osobny COUNT, bezpieczne klemowanie strony. */
    public static void open(Plugin plugin, Player player, Supplier<BazaarConfig> cfg,
                            BazaarService service, EconomyBridge economy, MessageFactory messages,
                            int page) {
        HexAuctionBazaarPlugin main = (HexAuctionBazaarPlugin) plugin;
        var orders = main.orderService();
        int safePage = Math.max(0, page);
        orders.listAllPaged(player.getUniqueId(), PAGE_SIZE, safePage * PAGE_SIZE)
                .thenCombine(orders.countAll(player.getUniqueId()),
                        (list, count) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            int totalPages = Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE);
                            int clamped = Math.min(Math.max(0, safePage), totalPages - 1);
                            if (clamped != safePage) {
                                // Strona poza zakresem (np. po anulowaniu/usunięciu) -> otwórz raz na dozwolonej.
                                open(plugin, player, cfg, service, economy, messages, clamped);
                                return;
                            }
                            render(plugin, player, list, cfg, service, economy, messages,
                                    clamped, totalPages, count);
                        }));
    }

    private static void render(Plugin plugin, Player player, List<BazaarOrder> list,
                                Supplier<BazaarConfig> cfg, BazaarService service,
                                EconomyBridge economy, MessageFactory messages,
                                int page, int totalPages, int count) {
        BazaarConfig c = cfg.get();
        HexAuctionBazaarPlugin main = (HexAuctionBazaarPlugin) plugin;
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.BAZAAR_QUANTITY);
        Inventory inv = Bukkit.createInventory(holder, SIZE, LegacyFormat.component(c.ordersGuiTitle()));
        holder.bindInventory(inv);

        int slot = 0;
        for (BazaarOrder o : list) {
            if (slot >= 45) break;
            String sideStr = messages.raw(o.side() == OrderSide.BUY
                    ? "bazaar.order-side-buy" : "bazaar.order-side-sell", null);
            Material iconMat = o.side() == OrderSide.BUY ? Material.PAPER : Material.WRITABLE_BOOK;

            List<String> lore = new ArrayList<>();
            lore.add(messages.raw("bazaar.gui.order-line-lore-1", placeholders("item", o.itemKey())));
            lore.add(messages.raw("bazaar.gui.order-line-lore-2",
                    placeholders("remaining", String.valueOf(o.amountRemaining()),
                            "total", String.valueOf(o.amountTotal()))));
            lore.add(messages.raw("bazaar.gui.order-line-lore-3",
                    placeholders("price", economy.format(o.pricePerUnit()))));
            lore.add(messages.raw("bazaar.gui.order-line-lore-4",
                    placeholders("state", stateLabel(messages, o.state()))));
            lore.add("");
            final boolean isOpen = o.state().isOpen();
            final boolean isCancelled = o.state() == OrderState.CANCELLED;
            if (isOpen) {
                lore.add(messages.raw("bazaar.gui.order-line-lore-5", null));
            } else if (isCancelled) {
                lore.add(messages.raw("bazaar.gui.order-line-cancelled-status", null));
                lore.add(messages.raw("bazaar.gui.order-line-remove-hint", null));
            }

            ItemStack icon = GuiFrame.button(iconMat,
                    messages.raw("bazaar.gui.order-line-item",
                            placeholders("side", sideStr, "id", String.valueOf(o.id()))),
                    lore);
            inv.setItem(slot, icon);
            final long orderId = o.id();
            holder.setSlotAction(slot, ctx -> {
                if (!ctx.isRight()) return;
                if (isOpen) {
                    cancelOrder(plugin, main, ctx.player(), orderId, cfg, service, economy, messages, page);
                } else if (isCancelled) {
                    removeCancelled(plugin, main, ctx.player(), orderId, cfg, service, economy, messages, page);
                }
            });
            slot++;
        }

        if (count == 0) {
            inv.setItem(SLOT_EMPTY_STATE, GuiFrame.button(Material.BARRIER,
                    messages.raw("bazaar.gui.orders-empty-title", null),
                    List.of(messages.raw("bazaar.gui.orders-empty-lore", null))));
        }

        ItemStack back = GuiFrame.button(Material.BARRIER, messages.raw("bazaar.gui.back", null));
        inv.setItem(SLOT_BACK, back);
        holder.setSlotAction(SLOT_BACK,
                ctx -> BazaarMainGui.open(plugin, ctx.player(), cfg, service, economy, messages));

        // Poprzednia strona (tylko gdy istnieje).
        if (page > 0) {
            inv.setItem(SLOT_PREV, GuiFrame.button(Material.ARROW,
                    messages.raw("bazaar.gui.prev-page", null)));
            holder.setSlotAction(SLOT_PREV,
                    ctx -> open(plugin, ctx.player(), cfg, service, economy, messages, page - 1));
        }
        // Info o stronie + odświeżenie (klik).
        ItemStack info = GuiFrame.button(Material.CLOCK, messages.raw("bazaar.gui.orders-page-info",
                placeholders("page", String.valueOf(page + 1),
                        "total", String.valueOf(totalPages),
                        "count", String.valueOf(count))));
        inv.setItem(SLOT_INFO, info);
        holder.setSlotAction(SLOT_INFO,
                ctx -> open(plugin, ctx.player(), cfg, service, economy, messages, page));
        // Następna strona (tylko gdy istnieje).
        if (page < totalPages - 1) {
            inv.setItem(SLOT_NEXT, GuiFrame.button(Material.ARROW,
                    messages.raw("bazaar.gui.next-page", null)));
            holder.setSlotAction(SLOT_NEXT,
                    ctx -> open(plugin, ctx.player(), cfg, service, economy, messages, page + 1));
        }

        ItemStack close = GuiFrame.button(Material.BARRIER, messages.raw("bazaar.gui.close", null));
        inv.setItem(SLOT_CLOSE, close);
        holder.setSlotAction(SLOT_CLOSE, ctx -> ctx.player().closeInventory());

        GuiFrame.fillEmpty(inv, GuiFrame.materialOrDefault(c.frameMaterial(), Material.GRAY_STAINED_GLASS_PANE));
        player.openInventory(inv);
    }

    private static void cancelOrder(Plugin plugin, HexAuctionBazaarPlugin main, Player player, long orderId,
                                    Supplier<BazaarConfig> cfg, BazaarService service,
                                    EconomyBridge economy, MessageFactory messages, int page) {
        main.orderService().cancel(player, orderId).thenAccept(res ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    switch (res) {
                        case OK -> messages.send(player, "bazaar.order-cancelled",
                                placeholders("id", String.valueOf(orderId)));
                        case NOT_FOUND -> messages.send(player, "bazaar.order-not-found",
                                placeholders("id", String.valueOf(orderId)));
                        case NOT_OWNER -> messages.send(player, "bazaar.order-not-yours");
                        case NOT_OPEN -> messages.send(player, "bazaar.order-not-open");
                        case NO_PERMISSION -> messages.send(player, "common.no-permission");
                        case DB_FAILED -> messages.send(player, "common.db-error");
                    }
                    // Ta sama (klemowana) strona - open() sam skoryguje, jeśli wypadła poza zakres.
                    open(plugin, player, cfg, service, economy, messages, page);
                }));
    }

    private static void removeCancelled(Plugin plugin, HexAuctionBazaarPlugin main, Player player, long orderId,
                                        Supplier<BazaarConfig> cfg, BazaarService service,
                                        EconomyBridge economy, MessageFactory messages, int page) {
        main.orderService().removeCancelled(player, orderId).thenAccept(res ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    switch (res) {
                        case OK -> messages.send(player, "bazaar.order-removed",
                                placeholders("id", String.valueOf(orderId)));
                        case NOT_FOUND -> messages.send(player, "bazaar.order-not-found",
                                placeholders("id", String.valueOf(orderId)));
                        case NOT_OWNER -> messages.send(player, "bazaar.order-not-yours");
                        case NOT_CANCELLED -> messages.send(player, "bazaar.order-remove-not-cancelled");
                        case NO_PERMISSION -> messages.send(player, "common.no-permission");
                        case DB_FAILED -> messages.send(player, "common.db-error");
                    }
                    open(plugin, player, cfg, service, economy, messages, page);
                }));
    }

    private static String stateLabel(MessageFactory messages, OrderState state) {
        String key = "bazaar.order-state." + state.name().toLowerCase(Locale.ROOT);
        String label = messages.raw(key, null);
        return label.startsWith("&cmissing") ? state.name() : label;
    }
}
