package hex.auctionbazaar.auction.gui;

import hex.auctionbazaar.HexAuctionBazaarPlugin;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.config.AuctionConfig;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static hex.auctionbazaar.util.MessageFactory.placeholders;

/**
 * GUI wystawiania przedmiotu na aukcje.
 * Nie przyjmuje przedmiotu do zadedykowanego slotu (unikamy dupe-risk-a
 * przez wymuszona kotwice na przedmiocie z reki). Uzytkownik:
 *  1. Trzyma przedmiot w rece.
 *  2. Klika preset ceny lub "Własna cena" (znak).
 *  3. Serwis {@link AuctionService#sellItemInHand} sam snapshotuje i usuwa
 *     przedmiot atomowo, chroniac przed race-item-change.
 *
 * Dzieki temu nie mamy stanu inventory-w-GUI ktore trzeba by odzyskiwac
 * w razie zamkniecia okna.
 */
public final class AuctionSellGui {

    private static final int ROWS = 5;
    private static final int SIZE = ROWS * 9;

    private static final int SLOT_ITEM_PREVIEW = 4;
    private static final int SLOT_INFO = 22;
    private static final int SLOT_CANCEL = 40;
    private static final int SLOT_CLOSE = 44;
    // Presety cen w rzedzie 2 (sloty 10-16, do 7 presetow) - wypelnianie od lewej.
    private static final int[] PRESET_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    // Custom price slot: rzad 4 (aby oddzielic wizualnie).
    private static final int SLOT_CUSTOM_PRICE = 31;

    public static void open(Plugin plugin, Player player, Supplier<AuctionConfig> cfg,
                            AuctionService service, EconomyBridge economy, MessageFactory messages) {
        render(plugin, player, cfg.get(), service, economy, messages);
    }

    private static void render(Plugin plugin, Player player, AuctionConfig cfg,
                                AuctionService service, EconomyBridge economy, MessageFactory messages) {
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.AUCTION_SELL);
        Inventory inv = Bukkit.createInventory(holder, SIZE, LegacyFormat.component(cfg.sellTitle()));
        holder.bindInventory(inv);

        // Podglad przedmiotu z reki
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand != null && inHand.getType() != Material.AIR) {
            ItemStack preview = inHand.clone();
            var meta = preview.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add(messages.raw("auction.gui.sell-item-slot-title", null));
                meta.lore(LegacyFormat.components(lore));
                preview.setItemMeta(meta);
            }
            inv.setItem(SLOT_ITEM_PREVIEW, preview);
        } else {
            inv.setItem(SLOT_ITEM_PREVIEW, GuiFrame.button(Material.BARRIER,
                    messages.raw("auction.gui.sell-item-slot-empty", null)));
        }

        // Info banner
        inv.setItem(SLOT_INFO, GuiFrame.button(Material.PAPER,
                messages.raw("auction.gui.sell-price-info-1", null),
                List.of(messages.raw("auction.gui.sell-price-info-2",
                        placeholders("min", cfg.minPrice().toPlainString(),
                                "max", cfg.maxPrice().toPlainString())))));

        // Presety cen
        List<BigDecimal> presets = cfg.sellPricePresets();
        int placed = 0;
        for (int i = 0; i < PRESET_SLOTS.length && i < presets.size(); i++) {
            BigDecimal p = presets.get(i);
            if (!cfg.priceInRange(p)) continue;
            int slot = PRESET_SLOTS[placed++];
            ItemStack btn = GuiFrame.button(Material.GOLD_INGOT,
                    messages.raw("auction.gui.sell-price-preset-title",
                            placeholders("price", economy.format(p))),
                    List.of(messages.raw("auction.gui.sell-price-preset-lore", null)));
            inv.setItem(slot, btn);
            final BigDecimal chosen = p;
            holder.setSlotAction(slot, ctx -> submitSell(plugin, ctx.player(), chosen,
                    cfg, service, economy, messages));
        }

        // Custom price
        ItemStack custom = GuiFrame.button(Material.OAK_SIGN,
                messages.raw("auction.gui.sell-price-custom-title", null),
                List.of(messages.raw("auction.gui.sell-price-custom-lore-1", null),
                        messages.raw("auction.gui.sell-price-custom-lore-2",
                                placeholders("min", cfg.minPrice().toPlainString(),
                                        "max", cfg.maxPrice().toPlainString()))));
        inv.setItem(SLOT_CUSTOM_PRICE, custom);
        holder.setSlotAction(SLOT_CUSTOM_PRICE, ctx -> promptCustomPrice(plugin, ctx.player(),
                cfg, service, economy, messages));

        // Cancel / close
        ItemStack cancel = GuiFrame.button(Material.RED_WOOL,
                messages.raw("auction.gui.confirm-cancel-button", null));
        inv.setItem(SLOT_CANCEL, cancel);
        holder.setSlotAction(SLOT_CANCEL, ctx -> ctx.player().closeInventory());

        ItemStack close = GuiFrame.button(Material.BARRIER,
                messages.raw("bazaar.gui.close", null));
        inv.setItem(SLOT_CLOSE, close);
        holder.setSlotAction(SLOT_CLOSE, ctx -> ctx.player().closeInventory());

        GuiFrame.fillEmpty(inv, GuiFrame.materialOrDefault(cfg.frameMaterial(), Material.BLACK_STAINED_GLASS_PANE));
        player.openInventory(inv);
    }

    private static void promptCustomPrice(Plugin plugin, Player player, AuctionConfig cfg,
                                          AuctionService service, EconomyBridge economy,
                                          MessageFactory messages) {
        HexAuctionBazaarPlugin main = (HexAuctionBazaarPlugin) plugin;
        if (main.signPrompt() == null) {
            messages.send(player, "common.input-invalid");
            return;
        }
        main.signPrompt().promptNumber(player,
                messages.raw("auction.sell-flow.prompt-price", null),
                v -> {
                    if (v == null || v.signum() <= 0 || !cfg.priceInRange(v)) {
                        messages.send(player, "auction.sell-flow.price-out-of-range",
                                placeholders("min", cfg.minPrice().toPlainString(),
                                        "max", cfg.maxPrice().toPlainString()));
                        open(plugin, player, () -> cfg, service, economy, messages);
                        return;
                    }
                    submitSell(plugin, player, v, cfg, service, economy, messages);
                });
    }

    private static void submitSell(Plugin plugin, Player player, BigDecimal price, AuctionConfig cfg,
                                    AuctionService service, EconomyBridge economy, MessageFactory messages) {
        service.sellItemInHand(player, price).thenAccept(outcome ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    switch (outcome.result()) {
                        case OK -> messages.send(player, "auction.listing-created",
                                placeholders("id", String.valueOf(outcome.listingId()),
                                        "price", economy.format(price)));
                        case NO_ITEM -> messages.send(player, "auction.sell-flow.item-mismatch");
                        case INVALID_PRICE -> messages.send(player, "auction.sell-flow.price-out-of-range",
                                placeholders("min", cfg.minPrice().toPlainString(),
                                        "max", cfg.maxPrice().toPlainString()));
                        case TOO_MANY -> messages.send(player, "auction.too-many-listings",
                                placeholders("max", String.valueOf(cfg.maxActiveListingsPerPlayer())));
                        case ECONOMY_FAILED -> messages.send(player, "common.economy-missing");
                        case DB_FAILED -> messages.send(player, "common.schema-not-ready");
                    }
                    player.closeInventory();
                }));
    }
}
