package hex.auctionbazaar.auction.gui;

import hex.auctionbazaar.HexAuctionBazaarPlugin;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.config.AuctionConfig;
import hex.auctionbazaar.gui.GuiFrame;
import hex.auctionbazaar.gui.GuiHolder;
import hex.auctionbazaar.gui.SignPrompt;
import hex.auctionbazaar.util.LegacyFormat;
import hex.auctionbazaar.util.MessageFactory;
import hex.auctionbazaar.util.SaleFeeResolver;
import hex.auctionbazaar.util.SaleTax;
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
 * GUI wystawiania przedmiotu na aukcję (punkt #10 / #11).
 *
 * Bez presetów cen - jest tylko jeden przycisk „Własna cena”, który uruchamia
 * naprawioną tabliczkę/czat. Po wpisaniu ceny pokazujemy przejrzystą
 * ZUSAMMENFASSUNG (brutto / podatek / netto), a dopiero jej zatwierdzenie
 * tworzy aukcję. Przedmiot nie trafia do dedykowanego slotu (kotwica na ręce),
 * dzięki czemu nie ma stanu inventory do odzyskiwania po zamknięciu okna.
 */
public final class AuctionSellGui {

    private static final int ROWS = 5;
    private static final int SIZE = ROWS * 9;

    // Ekran wyboru ceny.
    private static final int SLOT_ITEM_PREVIEW = 13;
    private static final int SLOT_CUSTOM_PRICE = 31;
    private static final int SLOT_BACK = 40;

    // Ekran podsumowania.
    private static final int SLOT_SUMMARY_INFO = 22;
    private static final int SLOT_CONFIRM = 29;
    private static final int SLOT_CHANGE_PRICE = 33;

    public static void open(Plugin plugin, Player player, Supplier<AuctionConfig> cfg,
                            AuctionService service, EconomyBridge economy, MessageFactory messages) {
        renderChoosePrice(plugin, player, cfg, service, economy, messages);
    }

    // ------------------------------------------------------------ choose-price screen

    private static void renderChoosePrice(Plugin plugin, Player player, Supplier<AuctionConfig> cfgSup,
                                          AuctionService service, EconomyBridge economy,
                                          MessageFactory messages) {
        AuctionConfig cfg = cfgSup.get();
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.AUCTION_SELL);
        Inventory inv = Bukkit.createInventory(holder, SIZE, LegacyFormat.component(cfg.sellTitle()));
        holder.bindInventory(inv);

        inv.setItem(SLOT_ITEM_PREVIEW, itemPreview(player, messages));

        ItemStack custom = GuiFrame.button(Material.OAK_SIGN,
                messages.raw("auction.gui.sell-price-custom-title", null),
                List.of(messages.raw("auction.gui.sell-price-custom-lore-1", null),
                        messages.raw("auction.gui.sell-price-custom-lore-2",
                                placeholders("min", cfg.minPrice().toPlainString(),
                                        "max", cfg.maxPrice().toPlainString()))));
        inv.setItem(SLOT_CUSTOM_PRICE, custom);
        holder.setSlotAction(SLOT_CUSTOM_PRICE, ctx -> promptCustomPrice(plugin, ctx.player(),
                cfgSup, service, economy, messages));

        ItemStack back = GuiFrame.button(Material.BARRIER,
                messages.raw("auction.gui.back", null));
        inv.setItem(SLOT_BACK, back);
        holder.setSlotAction(SLOT_BACK, ctx -> AuctionBrowseGui.open(plugin, ctx.player(),
                cfgSup, service, economy, messages));

        GuiFrame.fillEmpty(inv, GuiFrame.materialOrDefault(cfg.frameMaterial(), Material.BLACK_STAINED_GLASS_PANE));
        player.openInventory(inv);
    }

    private static ItemStack itemPreview(Player player, MessageFactory messages) {
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand == null || inHand.getType() == Material.AIR) {
            return GuiFrame.button(Material.BARRIER, messages.raw("auction.gui.sell-item-slot-empty", null));
        }
        // Zachowujemy prawdziwy podgląd (nazwa/lore/meta), dodając tylko krótką notkę.
        ItemStack preview = inHand.clone();
        var meta = preview.getItemMeta();
        if (meta != null) {
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            if (meta.hasLore() && meta.lore() != null) {
                lore.addAll(meta.lore());
            }
            lore.add(LegacyFormat.component(messages.raw("auction.gui.sell-item-slot-title", null)));
            meta.lore(lore);
            preview.setItemMeta(meta);
        }
        return preview;
    }

    private static void promptCustomPrice(Plugin plugin, Player player, Supplier<AuctionConfig> cfgSup,
                                          AuctionService service, EconomyBridge economy,
                                          MessageFactory messages) {
        HexAuctionBazaarPlugin main = (HexAuctionBazaarPlugin) plugin;
        if (main.signPrompt() == null) {
            messages.send(player, "common.input-invalid");
            return;
        }
        AuctionConfig cfg = cfgSup.get();
        main.signPrompt().promptNumber(player,
                messages.raw("auction.sell-flow.prompt-price", null),
                res -> {
                    // #9: rozłączne wyniki promptu. Anulacja/timeout/błąd/transport mają własny
                    // komunikat i (poza offline) przywracają widok wyboru ceny.
                    if (!res.isSuccess()) {
                        messages.send(player, SignPrompt.messageKey(res.outcome()));
                        if (res.outcome() != SignPrompt.PromptOutcome.TRANSPORT_FAILED) {
                            renderChoosePrice(plugin, player, cfgSup, service, economy, messages);
                        }
                        return;
                    }
                    // #7: normalizacja do skali 2 (HALF_UP) i granic DECIMAL(19,2) ZANIM cena
                    // trafi do podatku/ekonomii/DB/audytu oraz na ekran podsumowania.
                    BigDecimal price = hex.auctionbazaar.util.Money.normalize(res.value());
                    if (price == null || price.signum() <= 0 || !hex.auctionbazaar.util.Money.fits(price)
                            || !cfg.priceInRange(price)) {
                        messages.send(player, "auction.sell-flow.price-out-of-range",
                                placeholders("min", cfg.minPrice().toPlainString(),
                                        "max", cfg.maxPrice().toPlainString()));
                        renderChoosePrice(plugin, player, cfgSup, service, economy, messages);
                        return;
                    }
                    renderSummary(plugin, player, cfgSup, service, economy, messages, price);
                });
    }

    // ------------------------------------------------------------ summary screen

    private static void renderSummary(Plugin plugin, Player player, Supplier<AuctionConfig> cfgSup,
                                      AuctionService service, EconomyBridge economy,
                                      MessageFactory messages, BigDecimal price) {
        AuctionConfig cfg = cfgSup.get();
        BigDecimal pct = SaleFeeResolver.resolve(player::hasPermission, cfg.saleFeePercent(), cfg.saleFeeTiers());
        SaleTax.Breakdown tax = SaleTax.compute(price, pct);
        BigDecimal fee = cfg.listingFee() == null ? BigDecimal.ZERO : cfg.listingFee();
        BigDecimal economicNet = tax.net().subtract(fee);
        if (economicNet.signum() < 0) economicNet = BigDecimal.ZERO;

        GuiHolder holder = new GuiHolder(GuiHolder.Kind.AUCTION_SELL);
        Inventory inv = Bukkit.createInventory(holder, SIZE,
                LegacyFormat.component(messages.raw("auction.sell-flow.summary-title", null)));
        holder.bindInventory(inv);

        inv.setItem(SLOT_ITEM_PREVIEW, itemPreview(player, messages));

        List<String> summaryLore = new ArrayList<>();
        summaryLore.add(messages.raw("auction.gui.sell-summary-price",
                placeholders("gross", economy.format(tax.gross()))));
        if (fee.signum() > 0) {
            summaryLore.add(messages.raw("auction.gui.sell-summary-fee",
                    placeholders("fee", economy.format(fee))));
        }
        summaryLore.add(messages.raw("auction.gui.sell-summary-tax",
                placeholders("percent", tax.percent().toPlainString(),
                        "tax", economy.format(tax.tax()))));
        summaryLore.add(messages.raw("auction.gui.sell-summary-net",
                placeholders("net", economy.format(economicNet))));

        inv.setItem(SLOT_SUMMARY_INFO, GuiFrame.button(Material.GOLD_INGOT,
                messages.raw("auction.gui.sell-summary-gross",
                        placeholders("gross", economy.format(tax.gross()))),
                summaryLore));

        ItemStack confirm = GuiFrame.button(Material.LIME_WOOL,
                messages.raw("auction.gui.sell-confirm-button",
                        placeholders("gross", economy.format(tax.gross()))));
        inv.setItem(SLOT_CONFIRM, confirm);
        final BigDecimal shownPct = pct;
        holder.setSlotAction(SLOT_CONFIRM, ctx -> submitSell(plugin, ctx.player(), price, shownPct, cfgSup,
                service, economy, messages));

        ItemStack change = GuiFrame.button(Material.OAK_SIGN,
                messages.raw("auction.gui.sell-change-price", null),
                List.of(messages.raw("auction.gui.sell-price-custom-lore-1", null)));
        inv.setItem(SLOT_CHANGE_PRICE, change);
        holder.setSlotAction(SLOT_CHANGE_PRICE, ctx -> promptCustomPrice(plugin, ctx.player(),
                cfgSup, service, economy, messages));

        ItemStack back = GuiFrame.button(Material.BARRIER,
                messages.raw("auction.gui.back", null));
        inv.setItem(SLOT_BACK, back);
        holder.setSlotAction(SLOT_BACK, ctx -> AuctionBrowseGui.open(plugin, ctx.player(),
                cfgSup, service, economy, messages));

        GuiFrame.fillEmpty(inv, GuiFrame.materialOrDefault(cfg.frameMaterial(), Material.BLACK_STAINED_GLASS_PANE));
        player.openInventory(inv);
    }

    private static void submitSell(Plugin plugin, Player player, BigDecimal price, BigDecimal shownPct,
                                   Supplier<AuctionConfig> cfgSup, AuctionService service,
                                   EconomyBridge economy, MessageFactory messages) {
        AuctionConfig cfg = cfgSup.get();
        // Wiążemy pokazany procent - jeśli się zmienił, GUI pokaże podsumowanie ponownie.
        service.sellItemInHand(player, price, shownPct).thenAccept(outcome ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    switch (outcome.result()) {
                        case OK -> {
                            messages.send(player, "auction.listing-created",
                                    placeholders("id", String.valueOf(outcome.listingId()),
                                            "gross", economy.format(outcome.gross()),
                                            "tax", economy.format(outcome.tax()),
                                            "net", economy.format(outcome.net())));
                            player.closeInventory();
                        }
                        case TAX_CHANGED -> {
                            messages.send(player, "auction.tax-changed",
                                    placeholders("percent", outcome.taxPercent().toPlainString()));
                            renderSummary(plugin, player, cfgSup, service, economy, messages, price);
                        }
                        case NOT_ENOUGH_MONEY -> {
                            messages.send(player, "auction.not-enough-money-for-listing",
                                    placeholders("required", economy.format(outcome.required()),
                                            "fee", economy.format(outcome.listingFee()),
                                            "tax", economy.format(outcome.tax())));
                            player.closeInventory();
                        }
                        case ECONOMY_UNAVAILABLE -> { messages.send(player, "common.economy-missing"); player.closeInventory(); }
                        case ECONOMY_ERROR -> { messages.send(player, "auction.economy-error"); player.closeInventory(); }
                        case BUSY -> messages.send(player, "auction.sell-busy");
                        case FEATURE_DISABLED -> { messages.send(player, "common.feature-disabled"); player.closeInventory(); }
                        case NO_PERMISSION -> { messages.send(player, "common.no-permission"); player.closeInventory(); }
                        case COMPENSATION_FAILED -> { messages.send(player, "auction.compensation-failed"); player.closeInventory(); }
                        case NO_ITEM -> { messages.send(player, "auction.sell-flow.item-mismatch"); player.closeInventory(); }
                        case INVALID_PRICE -> {
                            messages.send(player, "auction.sell-flow.price-out-of-range",
                                    placeholders("min", cfg.minPrice().toPlainString(),
                                            "max", cfg.maxPrice().toPlainString()));
                            player.closeInventory();
                        }
                        case TOO_MANY -> {
                            messages.send(player, "auction.too-many-listings",
                                    placeholders("max", String.valueOf(outcome.limit())));
                            player.closeInventory();
                        }
                        case DB_FAILED -> { messages.send(player, "common.db-error"); player.closeInventory(); }
                    }
                }));
    }
}
