package hex.auctionbazaar.bazaar.gui;

import hex.auctionbazaar.HexAuctionBazaarPlugin;
import hex.auctionbazaar.bazaar.model.BazaarPrice;
import hex.auctionbazaar.bazaar.model.OrderSide;
import hex.auctionbazaar.bazaar.service.BazaarOrderService;
import hex.auctionbazaar.bazaar.service.BazaarService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.config.BazaarItemConfig;
import hex.auctionbazaar.gui.GuiFrame;
import hex.auctionbazaar.gui.GuiHolder;
import hex.auctionbazaar.gui.SignPrompt;
import hex.auctionbazaar.util.LegacyFormat;
import hex.auctionbazaar.util.MessageFactory;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

import static hex.auctionbazaar.util.MessageFactory.placeholders;

/**
 * GUI tworzenia zlecenia BUY-ORDER / SELL-OFFER dla danego przedmiotu.
 *
 * Uklad (rzedy):
 *  - rzad 1: display item + info
 *  - rzad 2-3: przyciski wyboru ilosci (1, 64, 576, wlasna)
 *  - rzad 4: przyciski wyboru ceny (sugerowana + wlasna przez znak)
 *  - rzad 5: przycisk potwierdzenia (widoczny gdy wybrane obie wartosci)
 *  - dolny rzad: back / close
 *
 * Stan: kluczowe wartosci trzymane w GuiHolder.state (amount, price).
 */
public final class BazaarOrderCreateGui {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private static final int SLOT_INFO = 4;
    private static final int SLOT_AMOUNT_1 = 10;
    private static final int SLOT_AMOUNT_64 = 11;
    private static final int SLOT_AMOUNT_576 = 12;
    private static final int SLOT_AMOUNT_CUSTOM = 13;

    private static final int SLOT_PRICE_SUGGESTED = 28;
    private static final int SLOT_PRICE_CUSTOM = 30;
    private static final int SLOT_PRICE_MIN = 32;
    private static final int SLOT_PRICE_MAX = 34;

    private static final int SLOT_CONFIRM = 40;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_CLOSE = 53;

    public static void open(Plugin plugin, Player player, String itemKey, OrderSide side,
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
                        () -> render(plugin, player, item, side, price, cfg, service, economy, messages,
                                null, null))
        );
    }

    private static void render(Plugin plugin, Player player, BazaarItemConfig item, OrderSide side,
                                BazaarPrice price, Supplier<BazaarConfig> cfg, BazaarService service,
                                EconomyBridge economy, MessageFactory messages,
                                Long amount, BigDecimal chosenPrice) {
        BazaarConfig c = cfg.get();
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.BAZAAR_ORDER_CREATE);
        String titleTemplate = side == OrderSide.BUY
                ? messages.raw("bazaar.gui.order-create-buy-title",
                        placeholders("item", item.displayName()))
                : messages.raw("bazaar.gui.order-create-sell-title",
                        placeholders("item", item.displayName()));
        String title = titleTemplate == null ? c.orderCreateGuiTitle() : titleTemplate;
        Inventory inv = Bukkit.createInventory(holder, SIZE, LegacyFormat.component(title));
        holder.bindInventory(inv);
        if (amount != null) holder.putState("amount", amount);
        if (chosenPrice != null) holder.putState("price", chosenPrice);

        inv.setItem(SLOT_INFO, GuiFrame.button(item.material(),
                item.displayName(),
                List.of(messages.raw("bazaar.gui.order-create-amount-info", null))));

        // Amount buttons
        placeAmountButton(inv, holder, plugin, item, side, price, cfg, service, economy, messages,
                SLOT_AMOUNT_1, 1L, amount, chosenPrice);
        placeAmountButton(inv, holder, plugin, item, side, price, cfg, service, economy, messages,
                SLOT_AMOUNT_64, 64L, amount, chosenPrice);
        placeAmountButton(inv, holder, plugin, item, side, price, cfg, service, economy, messages,
                SLOT_AMOUNT_576, 576L, amount, chosenPrice);
        // Custom amount slot
        ItemStack customAmt = GuiFrame.button(Material.OAK_SIGN,
                messages.raw("bazaar.gui.quantity-preset-custom", null),
                List.of(messages.raw("bazaar.gui.quantity-custom-lore", null)));
        inv.setItem(SLOT_AMOUNT_CUSTOM, customAmt);
        holder.setSlotAction(SLOT_AMOUNT_CUSTOM, ctx -> {
            HexAuctionBazaarPlugin main = (HexAuctionBazaarPlugin) plugin;
            if (main.signPrompt() == null) return;
            main.signPrompt().promptLong(ctx.player(),
                    messages.raw("bazaar.gui.quantity-preset-custom", null),
                    res -> {
                        // #9: nie-sukces -> właściwy komunikat i (poza offline) powrót do kreatora.
                        if (!res.isSuccess()) {
                            messages.send(ctx.player(), SignPrompt.messageKey(res.outcome()));
                            if (res.outcome() != SignPrompt.PromptOutcome.TRANSPORT_FAILED) {
                                render(plugin, ctx.player(), item, side, price, cfg, service, economy,
                                        messages, amount, chosenPrice);
                            }
                            return;
                        }
                        render(plugin, ctx.player(), item, side, price, cfg, service, economy,
                                messages, res.value(), chosenPrice);
                    });
        });

        // Suggested price
        BigDecimal suggested = suggestedPrice(price, side, item);
        ItemStack suggestedBtn = GuiFrame.button(Material.EMERALD,
                messages.raw("bazaar.gui.order-create-price-suggested",
                        placeholders("price", economy.format(suggested))),
                List.of(messages.raw("bazaar.gui.order-create-price-info", null)));
        inv.setItem(SLOT_PRICE_SUGGESTED, suggestedBtn);
        holder.setSlotAction(SLOT_PRICE_SUGGESTED, ctx -> render(plugin, ctx.player(), item, side,
                price, cfg, service, economy, messages, amount, suggested));

        // Custom price
        ItemStack customPrice = GuiFrame.button(Material.OAK_SIGN,
                messages.raw("bazaar.gui.price-preset-custom", null),
                List.of(messages.raw("bazaar.gui.price-custom-lore", null),
                        messages.raw("auction.gui.sell-price-custom-lore-2",
                                placeholders("min", item.minPrice().toPlainString(),
                                        "max", item.maxPrice().toPlainString()))));
        inv.setItem(SLOT_PRICE_CUSTOM, customPrice);
        holder.setSlotAction(SLOT_PRICE_CUSTOM, ctx -> {
            HexAuctionBazaarPlugin main = (HexAuctionBazaarPlugin) plugin;
            if (main.signPrompt() == null) return;
            main.signPrompt().promptNumber(ctx.player(),
                    messages.raw("bazaar.order-flow.prompt-price", null),
                    res -> {
                        if (!res.isSuccess()) {
                            messages.send(ctx.player(), SignPrompt.messageKey(res.outcome()));
                            if (res.outcome() != SignPrompt.PromptOutcome.TRANSPORT_FAILED) {
                                render(plugin, ctx.player(), item, side, price, cfg, service, economy,
                                        messages, amount, chosenPrice);
                            }
                            return;
                        }
                        // #7: normalizacja do skali 2 i granic DECIMAL(19,2) przed walidacją/DB/ekonomią.
                        BigDecimal p = hex.auctionbazaar.util.Money.normalize(res.value());
                        if (p == null || p.signum() <= 0 || !hex.auctionbazaar.util.Money.fits(p)
                                || p.compareTo(item.minPrice()) < 0
                                || p.compareTo(item.maxPrice()) > 0) {
                            messages.send(ctx.player(), "bazaar.invalid-price",
                                    placeholders("min", item.minPrice().toPlainString(),
                                            "max", item.maxPrice().toPlainString()));
                            render(plugin, ctx.player(), item, side, price, cfg, service, economy,
                                    messages, amount, chosenPrice);
                            return;
                        }
                        render(plugin, ctx.player(), item, side, price, cfg, service, economy,
                                messages, amount, p);
                    });
        });

        // Min/max price preview
        inv.setItem(SLOT_PRICE_MIN, GuiFrame.button(Material.GRAY_DYE,
                "&7Min &f" + item.minPrice().toPlainString(), List.of()));
        inv.setItem(SLOT_PRICE_MAX, GuiFrame.button(Material.YELLOW_DYE,
                "&eMax &f" + item.maxPrice().toPlainString(), List.of()));

        // Confirm button (enabled only when both selected)
        if (amount != null && chosenPrice != null) {
            BigDecimal total = chosenPrice.multiply(new BigDecimal(amount));
            ItemStack confirm = GuiFrame.button(Material.LIME_WOOL,
                    messages.raw("bazaar.gui.order-create-confirm", null),
                    List.of(messages.raw("bazaar.gui.order-create-confirm-lore-1",
                                    placeholders("amount", String.valueOf(amount))),
                            messages.raw("bazaar.gui.order-create-confirm-lore-2",
                                    placeholders("price", economy.format(chosenPrice))),
                            messages.raw("bazaar.gui.order-create-confirm-lore-3",
                                    placeholders("total", economy.format(total)))));
            inv.setItem(SLOT_CONFIRM, confirm);
            holder.setSlotAction(SLOT_CONFIRM, ctx -> submitOrder(plugin, ctx.player(),
                    item, side, amount, chosenPrice, cfg, service, economy, messages));
        } else {
            ItemStack pending = GuiFrame.button(Material.GRAY_WOOL,
                    "&7" + messages.raw("bazaar.gui.order-create-confirm", null));
            inv.setItem(SLOT_CONFIRM, pending);
        }

        // Back / close
        ItemStack back = GuiFrame.button(Material.BARRIER,
                messages.raw("bazaar.gui.back", null));
        inv.setItem(SLOT_BACK, back);
        holder.setSlotAction(SLOT_BACK, ctx -> BazaarItemGui.open(plugin, ctx.player(),
                item.key(), cfg, service, economy, messages));

        ItemStack close = GuiFrame.button(Material.BARRIER,
                messages.raw("bazaar.gui.close", null));
        inv.setItem(SLOT_CLOSE, close);
        holder.setSlotAction(SLOT_CLOSE, ctx -> ctx.player().closeInventory());

        GuiFrame.fillEmpty(inv, GuiFrame.materialOrDefault(c.frameMaterial(), Material.GRAY_STAINED_GLASS_PANE));
        player.openInventory(inv);
    }

    private static void placeAmountButton(Inventory inv, GuiHolder holder, Plugin plugin,
                                            BazaarItemConfig item, OrderSide side, BazaarPrice price,
                                            Supplier<BazaarConfig> cfg, BazaarService service,
                                            EconomyBridge economy, MessageFactory messages,
                                            int slot, long qty, Long selectedAmount, BigDecimal chosenPrice) {
        String presetKey = qty == 1L ? "bazaar.gui.quantity-preset-1"
                : qty == 64L ? "bazaar.gui.quantity-preset-64"
                : qty == 576L ? "bazaar.gui.quantity-preset-576"
                : null;
        String label = presetKey != null ? messages.raw(presetKey, null) : "&a" + qty + " szt.";
        Material mat = qty == 576L ? Material.CHEST : item.material();
        int stackShow = (int) Math.min(64, Math.max(1, qty));
        ItemStack btn = new ItemStack(mat, stackShow);
        var meta = btn.getItemMeta();
        if (meta != null) {
            String suffix = (selectedAmount != null && selectedAmount == qty) ? " &7(wybrane)" : "";
            meta.displayName(LegacyFormat.component(label + suffix));
            btn.setItemMeta(meta);
        }
        inv.setItem(slot, btn);
        holder.setSlotAction(slot, ctx -> render(plugin, ctx.player(), item, side, price, cfg,
                service, economy, messages, qty, chosenPrice));
    }

    private static BigDecimal suggestedPrice(BazaarPrice price, OrderSide side, BazaarItemConfig item) {
        if (price != null) {
            return side == OrderSide.BUY ? price.buyPrice() : price.sellPrice();
        }
        return item.basePrice();
    }

    private static void submitOrder(Plugin plugin, Player player, BazaarItemConfig item, OrderSide side,
                                     long amount, BigDecimal price, Supplier<BazaarConfig> cfg,
                                     BazaarService service, EconomyBridge economy, MessageFactory messages) {
        HexAuctionBazaarPlugin main = (HexAuctionBazaarPlugin) plugin;
        BazaarOrderService orders = main.orderService();
        java.util.concurrent.CompletableFuture<BazaarOrderService.PlaceOutcome> future =
                side == OrderSide.BUY
                        ? orders.placeBuyOrder(player, item.key(), amount, price)
                        : orders.placeSellOffer(player, item.key(), amount, price);
        future.thenAccept(outcome -> Bukkit.getScheduler().runTask(plugin, () -> {
            switch (outcome.result()) {
                case OK -> {
                    String msg = side == OrderSide.BUY
                            ? "bazaar.order-placed-buy" : "bazaar.order-placed-sell";
                    messages.send(player, msg, placeholders(
                            "id", String.valueOf(outcome.orderId()),
                            "amount", String.valueOf(amount),
                            "item", item.displayName(),
                            "price", economy.format(price),
                            "total", economy.format(outcome.totalReserved())));
                    BazaarItemGui.open(plugin, player, item.key(), cfg, service, economy, messages);
                }
                case UNKNOWN_ITEM -> messages.send(player, "bazaar.unknown-item",
                        placeholders("key", item.key()));
                case INVALID_QTY -> messages.send(player, "bazaar.invalid-quantity");
                case INVALID_PRICE -> messages.send(player, "bazaar.invalid-price",
                        placeholders("min", item.minPrice().toPlainString(),
                                "max", item.maxPrice().toPlainString()));
                case TOO_MANY_OPEN -> messages.send(player, "bazaar.order-too-many",
                        placeholders("max", String.valueOf(cfg.get().maxOrdersPerPlayer())));
                case NOT_ENOUGH_MONEY -> messages.send(player, "bazaar.not-enough-money");
                case NOT_ENOUGH_ITEMS -> messages.send(player, "bazaar.not-enough-items",
                        placeholders("item", item.displayName()));
                case ECONOMY_UNAVAILABLE -> messages.send(player, "common.economy-missing");
                case ECONOMY_ERROR -> messages.send(player, "common.economy-error");
                case DB_FAILED -> messages.send(player, "common.schema-not-ready");
                case FEATURE_DISABLED -> messages.send(player, "bazaar.order-feature-disabled");
                // Krytyczny stan kompensacji: dla SELL nie udało się zwrócić przedmiotów, dla BUY - środków.
                case COMPENSATION_FAILED -> messages.send(player, side == OrderSide.BUY
                        ? "bazaar.order-buy-refund-failed" : "bazaar.order-return-failed");
                case NO_PERMISSION -> messages.send(player, "common.no-permission");
                case BUSY -> messages.send(player, "bazaar.order-busy");
            }
        }));
    }
}
