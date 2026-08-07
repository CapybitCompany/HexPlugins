package hex.auctionbazaar.bazaar.gui;

import hex.auctionbazaar.HexAuctionBazaarPlugin;
import hex.auctionbazaar.bazaar.model.BazaarPrice;
import hex.auctionbazaar.bazaar.service.BazaarService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.config.BazaarItemConfig;
import hex.auctionbazaar.gui.GuiFrame;
import hex.auctionbazaar.gui.GuiHolder;
import hex.auctionbazaar.gui.SignPrompt;
import hex.auctionbazaar.util.LegacyFormat;
import hex.auctionbazaar.util.MessageFactory;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static hex.auctionbazaar.util.MessageFactory.placeholders;

/**
 * Menu pojedynczego przedmiotu w Bazarze.
 * Uklad:
 *  - slot 4: przedmiot z lore (aktualne ceny)
 *  - lewa polowa BUY (10, 11, 12 + custom 20 pod "64"): 4 przyciski (1, 64, 576, wlasna ilosc)
 *  - prawa polowa SELL (14, 15, 16 + custom 24 pod "64"): 4 przyciski (1, 64, 576, wlasna ilosc)
 *  - slot 39: BUY ORDER (kreator - PAPER)
 *  - slot 41: SELL OFFER (kreator - WRITABLE_BOOK)
 *  - slot 40: MOJE ZLECENIA
 *  - slot 45: COFNIJ, 49: ODSWIEZ, 53: ZAMKNIJ
 * Auto-refresh: kazde otwarcie rejestruje sesje w tickerze.
 */
public final class BazaarItemGui {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private static final int SLOT_INFO = 4;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_REFRESH = 49;
    private static final int SLOT_ORDERS = 40;
    private static final int SLOT_BUY_ORDER_CREATE = 39;
    private static final int SLOT_SELL_OFFER_CREATE = 41;
    private static final int SLOT_CLOSE = 53;

    // Sloty BUY: 3 presety (1, 64, 576) w rzędzie + custom-tabliczka wyśrodkowana POD "64".
    private static final int[] BUY_SLOTS = {10, 11, 12, 20};
    // Sloty SELL: 3 presety (1, 64, 576) w rzędzie + custom-tabliczka wyśrodkowana POD "64".
    private static final int[] SELL_SLOTS = {14, 15, 16, 24};

    public static void open(Plugin plugin, Player player, String itemKey,
                            Supplier<BazaarConfig> cfg, BazaarService service,
                            EconomyBridge economy, MessageFactory messages) {
        BazaarConfig c = cfg.get();
        BazaarItemConfig item = c.item(itemKey).orElse(null);
        if (item == null) {
            messages.send(player, "bazaar.unknown-item", placeholders("key", itemKey));
            return;
        }
        service.currentPrice(itemKey).thenAccept(price ->
                Bukkit.getScheduler().runTask(plugin,
                        () -> render(plugin, player, item, price, cfg, service, economy, messages))
        );
    }

    private static void render(Plugin plugin, Player player, BazaarItemConfig item, BazaarPrice price,
                                Supplier<BazaarConfig> cfg, BazaarService service,
                                EconomyBridge economy, MessageFactory messages) {
        BazaarConfig c = cfg.get();
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.BAZAAR_ITEM);
        String title = c.itemGuiTitle().replace("%display%", item.displayName());
        Inventory inv = Bukkit.createInventory(holder, SIZE, LegacyFormat.component(title));
        holder.bindInventory(inv);

        inv.setItem(SLOT_INFO, buildInfo(item, price, economy, messages));
        renderQuantityButtons(inv, holder, plugin, item, price, cfg, service, economy, messages, c, true);
        renderQuantityButtons(inv, holder, plugin, item, price, cfg, service, economy, messages, c, false);
        renderOrderCreators(inv, holder, plugin, item, cfg, service, economy, messages);
        renderControls(inv, holder, plugin, item, cfg, service, economy, messages);

        GuiFrame.fillEmpty(inv, GuiFrame.materialOrDefault(c.frameMaterial(), Material.GRAY_STAINED_GLASS_PANE));
        player.openInventory(inv);
        // Menu przedmiotu odświeżamy WYŁĄCZNIE manualnie (przycisk zegara) - bezpieczny
        // in-place refresh sum na przyciskach ilości nie jest tu zrobiony celowo (punkt #4).
    }

    private static ItemStack buildInfo(BazaarItemConfig item, BazaarPrice price,
                                        EconomyBridge economy, MessageFactory messages) {
        ItemStack info = new ItemStack(item.material());
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyFormat.component(item.displayName()));
            List<Component> lore = new ArrayList<>();
            if (price != null) {
                lore.add(LegacyFormat.component(messages.raw("bazaar.gui.lore-buy",
                        placeholders("price", economy.format(price.buyPrice())))));
                lore.add(LegacyFormat.component(messages.raw("bazaar.gui.lore-sell",
                        placeholders("price", economy.format(price.sellPrice())))));
            }
            meta.lore(lore);
            // Ukrywamy modyfikatory atrybutów (np. obrażenia miecza) - w menu liczy się cena, nie staty.
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            info.setItemMeta(meta);
        }
        return info;
    }

    /**
     * Ilosc-butki. Layout: 3 presety (1, 64, 576) + 1 slot na custom (OAK_SIGN).
     * Sygnaty ikon:
     *  - 1 sztuka: material przedmiotu (amount=1)
     *  - 64: material przedmiotu (amount=64)
     *  - 576: CHEST
     *  - custom: OAK_SIGN
     * Presety pobierane z configu (quantity-options). Jesli mniej niz 3
     * presetow, brakujace pola sa wypelnione zeby zawsze byl slot custom.
     */
    private static void renderQuantityButtons(Inventory inv, GuiHolder holder, Plugin plugin,
                                                BazaarItemConfig item, BazaarPrice price,
                                                Supplier<BazaarConfig> cfg, BazaarService service,
                                                EconomyBridge economy, MessageFactory messages,
                                                BazaarConfig c, boolean isBuy) {
        int[] slots = isBuy ? BUY_SLOTS : SELL_SLOTS;
        List<Long> options = c.quantityOptions();
        String actionLabel = messages.raw(isBuy ? "bazaar.gui.instant-buy" : "bazaar.gui.instant-sell", null);
        BigDecimal unit = price == null ? null
                : (isBuy ? price.buyPrice() : price.sellPrice());
        // 3 pierwsze sloty na presety, ostatni na custom.
        int presetCount = Math.min(slots.length - 1, options.size());
        for (int i = 0; i < presetCount; i++) {
            long qty = options.get(i);
            int slot = slots[i];
            BigDecimal total = unit == null ? BigDecimal.ZERO
                    : unit.multiply(new BigDecimal(qty));
            ItemStack btn = presetIcon(item.material(), qty, actionLabel, messages, economy, unit, total);
            inv.setItem(slot, btn);
            final long finalQty = qty;
            holder.setSlotAction(slot, ctx -> {
                if (finalQty > Integer.MAX_VALUE) {
                    messages.send(ctx.player(), "bazaar.invalid-quantity");
                    return;
                }
                if (isBuy) doBuy(plugin, ctx.player(), item, (int) finalQty, service, economy, messages);
                else doSell(plugin, ctx.player(), item, (int) finalQty, service, economy, messages);
            });
        }
        // Custom slot z OAK_SIGN.
        int customSlot = slots[slots.length - 1];
        ItemStack customIcon = GuiFrame.button(Material.OAK_SIGN,
                actionLabel + " &7- " + messages.raw("bazaar.gui.quantity-preset-custom", null),
                List.of(messages.raw("bazaar.gui.quantity-custom-lore", null)));
        inv.setItem(customSlot, customIcon);
        holder.setSlotAction(customSlot, ctx -> {
            HexAuctionBazaarPlugin main = (HexAuctionBazaarPlugin) plugin;
            if (main.signPrompt() == null) {
                messages.send(ctx.player(), "bazaar.invalid-quantity");
                return;
            }
            main.signPrompt().promptLong(ctx.player(),
                    messages.raw("bazaar.gui.quantity-preset-custom", null),
                    res -> {
                        // #9: rozłączne wyniki. Nie-sukces -> właściwy komunikat i (poza offline)
                        // powrót do widoku ilości.
                        if (!res.isSuccess()) {
                            messages.send(ctx.player(), SignPrompt.messageKey(res.outcome()));
                            if (res.outcome() != SignPrompt.PromptOutcome.TRANSPORT_FAILED) {
                                open(plugin, ctx.player(), item.key(), cfg, service, economy, messages);
                            }
                            return;
                        }
                        long v = res.value();
                        if (v > Integer.MAX_VALUE) {
                            messages.send(ctx.player(), "bazaar.invalid-quantity");
                            open(plugin, ctx.player(), item.key(), cfg, service, economy, messages);
                            return;
                        }
                        int qty = (int) v;
                        if (isBuy) doBuy(plugin, ctx.player(), item, qty, service, economy, messages);
                        else doSell(plugin, ctx.player(), item, qty, service, economy, messages);
                    });
        });
    }

    private static ItemStack presetIcon(Material itemMat, long qty, String actionLabel,
                                         MessageFactory messages, EconomyBridge economy,
                                         BigDecimal unit, BigDecimal total) {
        Material icon;
        String presetName;
        if (qty == 1L) {
            icon = itemMat;
            presetName = messages.raw("bazaar.gui.quantity-preset-1", null);
        } else if (qty == 64L) {
            icon = itemMat;
            presetName = messages.raw("bazaar.gui.quantity-preset-64", null);
        } else if (qty == 576L) {
            icon = Material.CHEST;
            presetName = messages.raw("bazaar.gui.quantity-preset-576", null);
        } else {
            icon = itemMat;
            presetName = "&a" + qty + " szt.";
        }
        // Dopasuj widoczna liczbe na stacku (max stack = 64 zwykle).
        int stackShow = (int) Math.min(64, Math.max(1, qty));
        ItemStack btn = new ItemStack(icon, stackShow);
        ItemMeta meta = btn.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyFormat.component(actionLabel + " &7- " + presetName));
            List<Component> lore = new ArrayList<>();
            lore.add(LegacyFormat.component(messages.raw("bazaar.gui.lore-amount",
                    placeholders("amount", String.valueOf(qty)))));
            lore.add(LegacyFormat.component(messages.raw("bazaar.gui.lore-total",
                    placeholders("total", economy.format(total)))));
            lore.add(LegacyFormat.component(messages.raw("bazaar.gui.quantity-preset-lore", null)));
            meta.lore(lore);
            // Przyciski ilości używają materiału przedmiotu - chowamy jego staty (np. obrażenia).
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            btn.setItemMeta(meta);
        }
        return btn;
    }

    private static void doBuy(Plugin plugin, Player player, BazaarItemConfig item, int qty,
                                BazaarService service, EconomyBridge economy, MessageFactory messages) {
        if (!item.buyEnabled()) {
            messages.send(player, "bazaar.buy-disabled");
            return;
        }
        service.buy(player, item.key(), qty).thenAccept(outcome ->
                Bukkit.getScheduler().runTask(plugin,
                        () -> handleBuyOutcome(player, item, outcome, economy, messages)));
    }

    private static void doSell(Plugin plugin, Player player, BazaarItemConfig item, int qty,
                                 BazaarService service, EconomyBridge economy, MessageFactory messages) {
        if (!item.sellEnabled()) {
            messages.send(player, "bazaar.sell-disabled");
            return;
        }
        service.sell(player, item.key(), qty).thenAccept(outcome ->
                Bukkit.getScheduler().runTask(plugin,
                        () -> handleSellOutcome(player, item, qty, outcome, economy, messages)));
    }

    private static void renderOrderCreators(Inventory inv, GuiHolder holder, Plugin plugin,
                                             BazaarItemConfig item, Supplier<BazaarConfig> cfg,
                                             BazaarService service, EconomyBridge economy,
                                             MessageFactory messages) {
        ItemStack buyOrder = GuiFrame.button(Material.PAPER,
                messages.raw("bazaar.gui.buy-order", null),
                List.of(messages.raw("bazaar.gui.buy-order-lore-1", null),
                        messages.raw("bazaar.gui.buy-order-lore-2", null)));
        inv.setItem(SLOT_BUY_ORDER_CREATE, buyOrder);
        holder.setSlotAction(SLOT_BUY_ORDER_CREATE, ctx ->
                BazaarOrderCreateGui.open(plugin, ctx.player(), item.key(),
                        hex.auctionbazaar.bazaar.model.OrderSide.BUY,
                        cfg, service, economy, messages));

        ItemStack sellOffer = GuiFrame.button(Material.WRITABLE_BOOK,
                messages.raw("bazaar.gui.sell-offer", null),
                List.of(messages.raw("bazaar.gui.sell-offer-lore-1", null),
                        messages.raw("bazaar.gui.sell-offer-lore-2", null)));
        inv.setItem(SLOT_SELL_OFFER_CREATE, sellOffer);
        holder.setSlotAction(SLOT_SELL_OFFER_CREATE, ctx ->
                BazaarOrderCreateGui.open(plugin, ctx.player(), item.key(),
                        hex.auctionbazaar.bazaar.model.OrderSide.SELL,
                        cfg, service, economy, messages));

        ItemStack orders = GuiFrame.button(Material.BOOK,
                messages.raw("bazaar.gui.orders", null),
                List.of(messages.raw("bazaar.gui.orders-lore", null)));
        inv.setItem(SLOT_ORDERS, orders);
        holder.setSlotAction(SLOT_ORDERS, ctx -> BazaarOrdersGui.open(plugin, ctx.player(),
                cfg, service, economy, messages));
    }

    private static void renderControls(Inventory inv, GuiHolder holder, Plugin plugin,
                                        BazaarItemConfig item, Supplier<BazaarConfig> cfg,
                                        BazaarService service, EconomyBridge economy,
                                        MessageFactory messages) {
        ItemStack back = GuiFrame.button(Material.BARRIER,
                messages.raw("bazaar.gui.back", null));
        inv.setItem(SLOT_BACK, back);
        holder.setSlotAction(SLOT_BACK, ctx -> BazaarMainGui.open(plugin, ctx.player(),
                cfg, service, economy, messages));

        ItemStack refresh = GuiFrame.button(Material.CLOCK,
                messages.raw("bazaar.gui.refresh", null));
        inv.setItem(SLOT_REFRESH, refresh);
        holder.setSlotAction(SLOT_REFRESH, ctx -> open(plugin, ctx.player(), item.key(),
                cfg, service, economy, messages));

        ItemStack close = GuiFrame.button(Material.BARRIER,
                messages.raw("bazaar.gui.close", null));
        inv.setItem(SLOT_CLOSE, close);
        holder.setSlotAction(SLOT_CLOSE, ctx -> ctx.player().closeInventory());
    }

    private static void handleBuyOutcome(Player player, BazaarItemConfig item,
                                          BazaarService.BuyOutcome outcome,
                                          EconomyBridge economy, MessageFactory messages) {
        switch (outcome.result()) {
            case OK -> {
                messages.send(player, "bazaar.bought", placeholders(
                        "amount", String.valueOf(outcome.deliveredAmount()),
                        "item", item.displayName(),
                        "total", economy.format(outcome.total())));
                if (outcome.wentToClaim()) {
                    messages.send(player, "bazaar.inventory-full-claim");
                }
            }
            case NOT_ENOUGH_STOCK -> messages.send(player, "bazaar.not-enough-stock",
                    placeholders("stock", "0"));
            case NOT_ENOUGH_MONEY -> messages.send(player, "bazaar.not-enough-money");
            case BUY_DISABLED -> messages.send(player, "bazaar.buy-disabled");
            case UNKNOWN_ITEM -> messages.send(player, "bazaar.unknown-item",
                    placeholders("key", item.key()));
            case ECONOMY_UNAVAILABLE -> messages.send(player, "common.economy-missing");
            case ECONOMY_ERROR -> messages.send(player, "common.economy-error");
            case INVALID_QTY -> messages.send(player, "bazaar.invalid-quantity");
            case DB_FAILED -> messages.send(player, "common.db-error");
            case REFUNDED -> messages.send(player, "bazaar.buy-refunded");
            case REFUND_PENDING -> messages.send(player, "bazaar.buy-refund-pending");
            case OVERPAY_REFUND_PENDING -> {
                messages.send(player, "bazaar.bought", placeholders(
                        "amount", String.valueOf(outcome.deliveredAmount()),
                        "item", item.displayName(),
                        "total", economy.format(outcome.total())));
                if (outcome.wentToClaim()) {
                    messages.send(player, "bazaar.inventory-full-claim");
                }
                messages.send(player, "bazaar.overpay-refund-pending");
            }
            case COMPENSATION_FAILED -> messages.send(player, "bazaar.buy-critical-error");
            case FEATURE_DISABLED -> messages.send(player, "common.feature-disabled");
            case NO_PERMISSION -> messages.send(player, "common.no-permission");
        }
    }

    private static void handleSellOutcome(Player player, BazaarItemConfig item, int qty,
                                           BazaarService.SellOutcome outcome,
                                           EconomyBridge economy, MessageFactory messages) {
        switch (outcome.result()) {
            case OK -> messages.send(player, "bazaar.sold", placeholders(
                    "amount", String.valueOf(outcome.amountSold()),
                    "item", item.displayName(),
                    "total", economy.format(outcome.total())));
            case OK_PENDING_CLAIM -> {
                messages.send(player, "bazaar.sold", placeholders(
                        "amount", String.valueOf(outcome.amountSold()),
                        "item", item.displayName(),
                        "total", economy.format(outcome.total())));
                messages.send(player, "bazaar.sell-pending-claim");
            }
            case OK_REST_CLAIMED -> {
                messages.send(player, "bazaar.sold", placeholders(
                        "amount", String.valueOf(outcome.amountSold()),
                        "item", item.displayName(),
                        "total", economy.format(outcome.total())));
                messages.send(player, "bazaar.sell-rest-claimed");
            }
            case RETURN_FAILED -> messages.send(player, "bazaar.sell-return-failed");
            case FEATURE_DISABLED -> messages.send(player, "common.feature-disabled");
            case NO_PERMISSION -> messages.send(player, "common.no-permission");
            case NOTHING_SOLD -> messages.send(player, "bazaar.nothing-sold");
            case PAYOUT_FAILED -> messages.send(player, "bazaar.sell-payout-failed");
            case NOT_ENOUGH_ITEMS -> messages.send(player, "bazaar.not-enough-items",
                    placeholders("item", item.displayName()));
            case SELL_DISABLED -> messages.send(player, "bazaar.sell-disabled");
            case UNKNOWN_ITEM -> messages.send(player, "bazaar.unknown-item",
                    placeholders("key", item.key()));
            case ECONOMY_UNAVAILABLE -> messages.send(player, "common.economy-missing");
            case INVALID_QTY -> messages.send(player, "bazaar.invalid-quantity");
            case DB_FAILED -> messages.send(player, "common.db-error");
        }
    }
}
