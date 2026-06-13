package hex.auctionbazaar.bazaar.gui;

import hex.auctionbazaar.bazaar.model.BazaarPrice;
import hex.auctionbazaar.bazaar.service.BazaarService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.config.BazaarItemConfig;
import hex.auctionbazaar.gui.GuiHolder;
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

public final class BazaarItemGui {

    private static final int[] AMOUNT_OPTIONS = {1, 16, 64};

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
                Bukkit.getScheduler().runTask(plugin, () -> render(plugin, player, item, price, cfg, service, economy, messages))
        );
    }

    private static void render(Plugin plugin, Player player, BazaarItemConfig item, BazaarPrice price,
                               Supplier<BazaarConfig> cfg, BazaarService service,
                               EconomyBridge economy, MessageFactory messages) {
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.BAZAAR_ITEM);
        String title = cfg.get().itemGuiTitle().replace("%display%", item.displayName());
        Inventory inv = Bukkit.createInventory(holder, 27, LegacyFormat.component(title));
        holder.bindInventory(inv);

        ItemStack info = new ItemStack(item.material());
        ItemMeta infoMeta = info.getItemMeta();
        if (infoMeta != null) {
            infoMeta.displayName(LegacyFormat.component(item.displayName()));
            List<Component> lore = new ArrayList<>();
            if (price != null) {
                lore.add(LegacyFormat.component("&7Buy:  &e" + economy.format(price.buyPrice())));
                lore.add(LegacyFormat.component("&7Sell: &e" + economy.format(price.sellPrice())));
            }
            infoMeta.lore(lore);
            info.setItemMeta(infoMeta);
        }
        inv.setItem(4, info);

        // Buy-Buttons links, Sell-Buttons rechts.
        int[] buySlots = {10, 11, 12};
        int[] sellSlots = {14, 15, 16};

        for (int i = 0; i < AMOUNT_OPTIONS.length; i++) {
            int qty = AMOUNT_OPTIONS[i];
            int buySlot = buySlots[i];
            int sellSlot = sellSlots[i];

            ItemStack buyBtn = button(Material.LIME_WOOL, "&aBuy " + qty);
            ItemStack sellBtn = button(Material.RED_WOOL, "&cSell " + qty);
            // Buttons are clickable; transactions resolve in the async chains below.

            inv.setItem(buySlot, buyBtn);
            inv.setItem(sellSlot, sellBtn);

            holder.setSlotAction(buySlot, ctx -> {
                ctx.player().closeInventory();
                if (!item.buyEnabled()) {
                    messages.send(ctx.player(), "bazaar.buy-disabled");
                    return;
                }
                service.buy(ctx.player(), item.key(), qty).thenAccept(outcome ->
                        Bukkit.getScheduler().runTask(plugin, () -> handleBuyOutcome(ctx.player(), item, qty, outcome, messages)));
            });
            holder.setSlotAction(sellSlot, ctx -> {
                ctx.player().closeInventory();
                if (!item.sellEnabled()) {
                    messages.send(ctx.player(), "bazaar.sell-disabled");
                    return;
                }
                service.sell(ctx.player(), item.key(), qty).thenAccept(outcome ->
                        Bukkit.getScheduler().runTask(plugin, () -> handleSellOutcome(ctx.player(), item, qty, outcome, economy, messages)));
            });
        }

        player.openInventory(inv);
    }

    private static void handleBuyOutcome(Player player, BazaarItemConfig item, int qty,
                                         BazaarService.BuyOutcome outcome, MessageFactory messages) {
        switch (outcome.result()) {
            case OK -> {
                messages.send(player, "bazaar.bought", placeholders(
                        "amount", String.valueOf(qty),
                        "item", item.displayName(),
                        "total", outcome.total().toPlainString()));
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
            case INVALID_QTY -> messages.send(player, "bazaar.invalid-quantity");
            case DB_FAILED -> messages.send(player, "common.schema-not-ready");
        }
    }

    private static void handleSellOutcome(Player player, BazaarItemConfig item, int qty,
                                          BazaarService.SellOutcome outcome,
                                          EconomyBridge economy, MessageFactory messages) {
        switch (outcome.result()) {
            case OK -> messages.send(player, "bazaar.sold", placeholders(
                    "amount", String.valueOf(qty),
                    "item", item.displayName(),
                    "total", economy.format(outcome.total())));
            case NOT_ENOUGH_ITEMS -> messages.send(player, "bazaar.not-enough-items",
                    placeholders("item", item.displayName()));
            case SELL_DISABLED -> messages.send(player, "bazaar.sell-disabled");
            case UNKNOWN_ITEM -> messages.send(player, "bazaar.unknown-item",
                    placeholders("key", item.key()));
            case ECONOMY_UNAVAILABLE -> messages.send(player, "common.economy-missing");
            case INVALID_QTY -> messages.send(player, "bazaar.invalid-quantity");
            case DB_FAILED -> messages.send(player, "common.schema-not-ready");
        }
    }

    private static ItemStack button(Material material, String name) {
        ItemStack s = new ItemStack(material);
        ItemMeta meta = s.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyFormat.component(name));
            s.setItemMeta(meta);
        }
        return s;
    }
}
