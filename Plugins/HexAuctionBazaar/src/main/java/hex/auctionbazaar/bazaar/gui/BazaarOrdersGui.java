package hex.auctionbazaar.bazaar.gui;

import hex.auctionbazaar.HexAuctionBazaarPlugin;
import hex.auctionbazaar.bazaar.model.BazaarOrder;
import hex.auctionbazaar.bazaar.model.OrderSide;
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

import java.util.List;
import java.util.function.Supplier;

import static hex.auctionbazaar.util.MessageFactory.placeholders;

/**
 * GUI zarządzania zleceniami gracza (Bazaar Order Book).
 * Kliknij PPM aby anulować zlecenie i odzyskać środki/przedmioty.
 * Ikony: PAPER = BUY-order (kupno), WRITABLE_BOOK = SELL-offer (oferta).
 */
public final class BazaarOrdersGui {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_REFRESH = 49;
    private static final int SLOT_CLOSE = 53;
    private static final int SLOT_EMPTY_STATE = 22;

    public static void open(Plugin plugin, Player player, Supplier<BazaarConfig> cfg,
                            BazaarService service, EconomyBridge economy, MessageFactory messages) {
        HexAuctionBazaarPlugin main = (HexAuctionBazaarPlugin) plugin;
        var orders = main.orderService();
        orders.listAll(player.getUniqueId(), 45).thenAccept(list ->
                Bukkit.getScheduler().runTask(plugin,
                        () -> render(plugin, player, list, cfg, service, economy, messages)));
    }

    private static void render(Plugin plugin, Player player, List<BazaarOrder> list,
                                Supplier<BazaarConfig> cfg, BazaarService service,
                                EconomyBridge economy, MessageFactory messages) {
        BazaarConfig c = cfg.get();
        HexAuctionBazaarPlugin main = (HexAuctionBazaarPlugin) plugin;
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.BAZAAR_QUANTITY);
        Inventory inv = Bukkit.createInventory(holder, SIZE, LegacyFormat.component(c.ordersGuiTitle()));
        holder.bindInventory(inv);

        int slot = 0;
        for (BazaarOrder o : list) {
            if (slot >= 45) break;
            String sideStr = o.side() == OrderSide.BUY ? "&aKUPNO" : "&cSPRZEDAŻ";
            Material iconMat = o.side() == OrderSide.BUY ? Material.PAPER : Material.WRITABLE_BOOK;
            ItemStack icon = GuiFrame.button(iconMat,
                    messages.raw("bazaar.gui.order-line-item",
                            placeholders("side", sideStr, "id", String.valueOf(o.id()))),
                    List.of(
                            messages.raw("bazaar.gui.order-line-lore-1",
                                    placeholders("item", o.itemKey())),
                            messages.raw("bazaar.gui.order-line-lore-2",
                                    placeholders("remaining", String.valueOf(o.amountRemaining()),
                                            "total", String.valueOf(o.amountTotal()))),
                            messages.raw("bazaar.gui.order-line-lore-3",
                                    placeholders("price", economy.format(o.pricePerUnit()))),
                            messages.raw("bazaar.gui.order-line-lore-4",
                                    placeholders("state", o.state().name())),
                            "",
                            messages.raw("bazaar.gui.order-line-lore-5", null)
                    ));
            inv.setItem(slot, icon);
            final long orderId = o.id();
            final boolean isOpen = o.state().isOpen();
            holder.setSlotAction(slot, ctx -> {
                if (!ctx.isRight() || !isOpen) return;
                main.orderService().cancel(ctx.player(), orderId).thenAccept(res ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            switch (res) {
                                case OK -> messages.send(ctx.player(), "bazaar.order-cancelled",
                                        placeholders("id", String.valueOf(orderId)));
                                case NOT_FOUND -> messages.send(ctx.player(), "bazaar.order-not-found",
                                        placeholders("id", String.valueOf(orderId)));
                                case NOT_OWNER -> messages.send(ctx.player(), "bazaar.order-not-yours");
                                case NOT_OPEN -> messages.send(ctx.player(), "bazaar.order-not-open");
                                case DB_FAILED -> messages.send(ctx.player(), "common.schema-not-ready");
                            }
                            open(plugin, ctx.player(), cfg, service, economy, messages);
                        }));
            });
            slot++;
        }

        if (list.isEmpty()) {
            inv.setItem(SLOT_EMPTY_STATE, GuiFrame.button(Material.BARRIER,
                    messages.raw("bazaar.gui.orders-empty-title", null),
                    List.of(messages.raw("bazaar.gui.orders-empty-lore", null))));
        }

        ItemStack back = GuiFrame.button(Material.ARROW, messages.raw("bazaar.gui.back", null));
        inv.setItem(SLOT_BACK, back);
        holder.setSlotAction(SLOT_BACK,
                ctx -> BazaarMainGui.open(plugin, ctx.player(), cfg, service, economy, messages));

        ItemStack refresh = GuiFrame.button(Material.CLOCK, messages.raw("bazaar.gui.refresh", null));
        inv.setItem(SLOT_REFRESH, refresh);
        holder.setSlotAction(SLOT_REFRESH,
                ctx -> open(plugin, ctx.player(), cfg, service, economy, messages));

        ItemStack close = GuiFrame.button(Material.BARRIER, messages.raw("bazaar.gui.close", null));
        inv.setItem(SLOT_CLOSE, close);
        holder.setSlotAction(SLOT_CLOSE, ctx -> ctx.player().closeInventory());

        GuiFrame.fillEmpty(inv, GuiFrame.materialOrDefault(c.frameMaterial(), Material.GRAY_STAINED_GLASS_PANE));
        player.openInventory(inv);
    }
}
