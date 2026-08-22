package hexnpc.shop.gui;

import hexnpc.shop.PriceCalculator;
import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.config.ShopMessages;
import hexnpc.shop.economy.EconomyBridge;
import hexnpc.shop.inventory.ShopItemStackFactory;
import hexnpc.shop.item.HexCustomItemsBridge;
import hexnpc.shop.model.ShopItemActionType;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import hexnpc.shop.model.ShopCurrency;
import hexnpc.shop.model.ShopLayout;
import hexnpc.util.LegacyFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Buduje inventory shopu dla obu widoków: paginowany MAIN grid oraz DETAIL
 * z wyborem ilości. Builder jest bezstanowy — żywą konfigurację i bridge
 * ekonomii dostajemy w konstruktorze, więc układ, ceny i teksty zawsze
 * odzwierciedlają bieżącą konfigurację i walutę.
 */
public final class ShopGuiBuilder {

    /**
     * Fabryka inventory. Produkcyjnie tworzy inventory z tytułem Component
     * (nowoczesne API). Seam pozwala środowiskom testowym bez pełnej
     * implementacji GUI (np. MockBukkit, gdzie wariant z Component jest
     * niezaimplementowany) podstawić działającą fabrykę.
     */
    @FunctionalInterface
    public interface InventoryFactory {
        Inventory create(InventoryHolder holder, int size, Component title);
    }

    private static volatile InventoryFactory inventoryFactory = Bukkit::createInventory;

    /** Podmienia fabrykę inventory (null przywraca domyślną, produkcyjną). */
    public static void setInventoryFactory(InventoryFactory factory) {
        inventoryFactory = factory != null ? factory : Bukkit::createInventory;
    }

    private final ShopConfig config;
    private final EconomyBridge economy;
    private final HexCustomItemsBridge customItems;

    public ShopGuiBuilder(ShopConfig config, EconomyBridge economy) {
        this(config, economy, null);
    }

    public ShopGuiBuilder(ShopConfig config, EconomyBridge economy, HexCustomItemsBridge customItems) {
        this.config = config;
        this.economy = economy;
        this.customItems = customItems;
    }

    private ShopMessages msg() {
        return config.messages();
    }

    // ================= Widok główny (MAIN) =================

    public Inventory buildMain(Shop shop, int page) {
        return buildMain(shop, page, null);
    }

    /** Player-aware MAIN allows one-time products to render an owned/edit state. */
    public Inventory buildMain(Shop shop, int page, Player player) {
        ShopLayout layout = shop.layout();
        int totalPages = ShopPlacement.totalPages(shop);
        int safePage = ShopPlacement.clampPage(page, totalPages);

        Map<Integer, ShopItem> pageItems = ShopPlacement.itemsForPage(shop, safePage);
        Map<Integer, String> itemSlotMap = new LinkedHashMap<>();
        for (Map.Entry<Integer, ShopItem> e : pageItems.entrySet()) {
            itemSlotMap.put(e.getKey(), e.getValue().id());
        }

        ShopGuiHolder holder = ShopGuiHolder.main(shop, safePage, totalPages, itemSlotMap,
                layout.previousSlot(), layout.nextSlot(), layout.pageSlot());
        Inventory inv = inventoryFactory.create(holder, layout.size(), renderTitle(shop));
        holder.bind(inv);
        fillBackground(inv, layout);

        for (Map.Entry<Integer, ShopItem> e : pageItems.entrySet()) {
            inv.setItem(e.getKey(), buildIcon(shop, e.getValue(), player));
        }

        // Przyciski nawigacji renderujemy tylko, gdy odpowiednia strona istnieje.
        if (safePage > 0) {
            inv.setItem(layout.previousSlot(), navButton(Material.ARROW, msg().guiPreviousPage()));
        }
        if (safePage < totalPages - 1) {
            inv.setItem(layout.nextSlot(), navButton(Material.ARROW, msg().guiNextPage()));
        }
        inv.setItem(layout.pageSlot(), pageInfoButton(safePage, totalPages));
        return inv;
    }

    // ================= Widok szczegółów (DETAIL) =================

    /**
     * @param buyRemaining ile sztuk gracz może jeszcze dziś kupić
     *                     ({@link Integer#MAX_VALUE} = bez limitu)
     */
    public Inventory buildDetail(Shop shop, ShopItem item, int selectedQuantity,
                                 int originPage, int buyRemaining, SellAllQuote sellAllQuote) {
        ShopLayout layout = shop.layout();
        int qty = Math.max(1, selectedQuantity);

        int buySlot = item.hasBuyPrice() ? layout.detailBuySlot() : -1;
        int sellSlot = item.hasSellPrice() ? layout.detailSellSlot() : -1;
        int sellAllSlot = (config.enableSellAll() && item.hasSellPrice()) ? layout.detailSellAllSlot() : -1;
        int customSlot = config.enableCustomQuantity() ? layout.detailCustomQuantitySlot() : -1;
        int backSlot = layout.detailBackSlot();
        int previewSlot = layout.detailPreviewSlot();

        // Presety: łączymy wartości z config z dostępnymi slotami układu.
        // Mapę slot->ilość budujemy PRZED holderem (holder kopiuje ją od razu).
        List<Integer> presets = config.quantityPresets();
        List<Integer> presetSlotList = layout.quantityPresetSlots();
        int presetCount = Math.min(presets.size(), presetSlotList.size());
        Map<Integer, Integer> presetSlots = new LinkedHashMap<>();
        for (int i = 0; i < presetCount; i++) {
            presetSlots.put(presetSlotList.get(i), presets.get(i));
        }

        ShopGuiHolder holder = ShopGuiHolder.detail(shop, item.id(), qty, originPage, presetSlots,
                customSlot, buySlot, sellSlot, sellAllSlot, backSlot, previewSlot);
        Inventory inv = inventoryFactory.create(holder, layout.size(), renderTitle(shop));
        holder.bind(inv);
        fillBackground(inv, layout);

        inv.setItem(previewSlot, buildPreview(shop, item, qty));
        inv.setItem(layout.detailSelectedInfoSlot(), buildSelectedInfo(shop, item, qty));

        for (Map.Entry<Integer, Integer> entry : presetSlots.entrySet()) {
            inv.setItem(entry.getKey(), buildPresetButton(shop, item, entry.getValue(), qty, buyRemaining));
        }
        if (customSlot >= 0) {
            inv.setItem(customSlot, buildCustomQuantityButton());
        }
        if (buySlot >= 0) {
            inv.setItem(buySlot, buildBuyButton(shop, item, qty, buyRemaining));
        }
        if (sellSlot >= 0) {
            inv.setItem(sellSlot, buildSellButton(shop, item, qty));
        }
        if (sellAllSlot >= 0) {
            inv.setItem(sellAllSlot, buildSellAllButton(shop, item, sellAllQuote));
        }
        inv.setItem(backSlot, buildBackButton());
        return inv;
    }

    // ================= Prosty widok zakupu premium =================

    /**
     * Małe GUI 27: podgląd u góry, czerwona wełna „Cofnij" oraz zielona
     * wełna „Kup". Bez presetów ilości i bez sprzedaży.
     */
    public Inventory buildSinglePurchase(Shop shop, ShopItem item, int originPage) {
        final int size = 27;
        final int previewSlot = 4;
        final int backSlot = 20;
        final int buySlot = 24;
        ShopGuiHolder holder = ShopGuiHolder.detail(shop, item.id(), item.amount(), originPage, Map.of(),
                -1, buySlot, -1, -1, backSlot, previewSlot);
        Inventory inv = inventoryFactory.create(holder, size, renderTitle(shop));
        holder.bind(inv);
        // Widok premium dziedziczy filler konkretnego sklepu (np. czarne szyby),
        // a fillBackground() ukrywa tooltip dekoracyjnych paneli.
        fillBackground(inv, shop.layout());

        List<Component> previewLore = new ArrayList<>();
        if (item.hasBuyPrice()) {
            previewLore.add(Component.empty());
            previewLore.add(component(msg().guiBuyLine(), "price", formatPrice(shop, item.buyPrice())));
        }
        inv.setItem(previewSlot, hideFlags(displayStack(item, previewLore)));

        ItemStack back = namedItem(Material.RED_WOOL, "&cCofnij");
        inv.setItem(backSlot, back);

        ItemStack buy = new ItemStack(Material.GREEN_WOOL);
        ItemMeta buyMeta = buy.getItemMeta();
        if (buyMeta != null) {
            buyMeta.displayName(component("&aKup"));
            if (item.hasBuyPrice()) {
                buyMeta.lore(List.of(component("&7Cena: &f<price>", "price", formatPrice(shop, item.buyPrice()))));
            }
            buyMeta.addItemFlags(ItemFlag.values());
            buy.setItemMeta(buyMeta);
        }
        inv.setItem(buySlot, buy);
        return inv;
    }

    // ================= Widok potwierdzenia (CONFIRM) =================

    /**
     * Buduje modalny widok potwierdzenia dużej transakcji. {@code totalPrice}
     * i {@code quantity} to podgląd — przy potwierdzeniu są liczone ponownie
     * serwerowo.
     */
    public Inventory buildConfirmation(Shop shop, ShopItem item, ConfirmAction action,
                                       int quantity, java.math.BigDecimal totalPrice, int originPage) {
        ShopConfig.Confirmation cfg = config.confirmation();
        int size = cfg.size();
        ShopGuiHolder holder = ShopGuiHolder.confirm(shop, item.id(), action, quantity, originPage,
                cfg.confirmSlot(), cfg.cancelSlot(), cfg.previewSlot());
        Inventory inv = inventoryFactory.create(holder, size, renderTitle(shop));
        holder.bind(inv);
        fillBackgroundSize(inv, size);

        // Podgląd realnego itemu z ilością i ceną.
        List<Component> lore = new ArrayList<>();
        lore.add(component(msg().guiConfirmQuantity(), "amount", String.valueOf(quantity)));
        lore.add(component(msg().guiConfirmPrice(), "price", formatPrice(shop, totalPrice)));
        ItemStack preview = displayStack(item, lore);
        preview.setAmount(clampDisplayAmount(preview, quantity));
        inv.setItem(cfg.previewSlot(), hideFlags(preview));

        boolean buy = action == ConfirmAction.BUY;
        ItemStack confirm = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta cMeta = confirm.getItemMeta();
        if (cMeta != null) {
            cMeta.displayName(component(buy ? msg().guiConfirmBuy() : msg().guiConfirmSell()));
            cMeta.lore(List.of(
                    component(msg().guiConfirmQuantity(), "amount", String.valueOf(quantity)),
                    component(msg().guiConfirmPrice(), "price", formatPrice(shop, totalPrice))));
            cMeta.addItemFlags(ItemFlag.values());
            confirm.setItemMeta(cMeta);
        }
        inv.setItem(cfg.confirmSlot(), confirm);

        inv.setItem(cfg.cancelSlot(), namedItem(Material.BARRIER, msg().guiCancelButton()));
        return inv;
    }

    // ================= Elementy =================

    private Component renderTitle(Shop shop) {
        String pattern = config.titleFormat();
        String shopTitle = shop.title() == null || shop.title().isEmpty() ? shop.id() : shop.title();
        String rendered = LegacyFormat.replace(pattern, "<shop>", shopTitle);
        return LegacyFormat.component(rendered);
    }

    private void fillBackground(Inventory inv, ShopLayout layout) {
        fillBackground(inv, layout.fillerMaterial(), layout.fillerName());
    }

    /** Tło widoku potwierdzenia (bez własnego layoutu) — wypełniacz z domyślnego układu. */
    private void fillBackgroundSize(Inventory inv, int size) {
        ShopLayout dl = config.defaultLayout();
        fillBackground(inv, dl.fillerMaterial(), dl.fillerName());
    }

    private void fillBackground(Inventory inv, Material fillerMaterial, String fillerName) {
        ItemStack filler = new ItemStack(fillerMaterial);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyFormat.component(fillerName));
            meta.addItemFlags(ItemFlag.values());
            // Paper 1.21.11: pełne ukrycie dymka (czarnego prostokąta) przy
            // najechaniu na dekoracyjne sloty tła. Ustawiane WYŁĄCZNIE na
            // wypełniaczu — elementy interaktywne zachowują swoje dymki.
            meta.setHideTooltip(true);
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
    }

    private ItemStack buildIcon(Shop shop, ShopItem item, Player player) {
        List<Component> extra = new ArrayList<>();
        boolean ownedInteractive = player != null
                && item.oneTime().enabled()
                && item.hasOwnedAction()
                && player.hasPermission(item.oneTime().permission());
        if (ownedInteractive) {
            extra.add(Component.empty());
            extra.add(LegacyFormat.component("&aOdblokowano"));
            extra.add(LegacyFormat.component("&eKliknij, aby zarządzać."));
            return hideFlags(displayStack(item, extra));
        }
        if (item.hasBuyPrice() || item.hasSellPrice() || item.action().type() != ShopItemActionType.NONE) {
            extra.add(Component.empty());
        }
        if (item.hasBuyPrice()) {
            extra.add(component(msg().guiBuyLine(), "price", formatPrice(shop, item.buyPrice())));
        }
        if (item.hasSellPrice()) {
            extra.add(component(msg().guiSellLine(), "price", formatPrice(shop, item.sellPrice())));
        }
        if (item.action().type() == ShopItemActionType.PLAYER_COMMAND) {
            extra.add(component(msg().guiClickToOpen()));
        } else if (item.action().type() == ShopItemActionType.DETAILS) {
            extra.add(component(msg().guiClickForDetails()));
        }
        return hideFlags(displayStack(item, extra));
    }

    private ItemStack displayStack(ShopItem item, List<Component> extraLore) {
        ItemStack external = null;
        if (customItems != null && item.hasCustomIconItem()) {
            int amount = item.reward().type() == hexnpc.shop.model.ShopRewardType.HEX_CUSTOM_ITEM
                    ? item.reward().amount() : item.amount();
            external = customItems.create(item.iconCustomItemId(), amount).orElse(null);
        }
        return ShopItemStackFactory.displayStack(item, extraLore, external);
    }

    private ItemStack buildPreview(Shop shop, ShopItem item, int quantity) {
        List<Component> extra = new ArrayList<>();
        extra.add(Component.empty());
        if (item.hasBuyPrice()) {
            String total = formatPrice(shop, buyTotal(shop, item, quantity));
            extra.add(component(msg().guiBuyLine(), "price", total));
        }
        if (item.hasSellPrice()) {
            String total = formatPrice(shop, sellTotal(shop, item, quantity));
            extra.add(component(msg().guiSellLine(), "price", total));
        }
        ItemStack stack = displayStack(item, extra);
        return hideFlags(stack);
    }

    private ItemStack buildSelectedInfo(Shop shop, ShopItem item, int quantity) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(msg().guiSelectedQuantity(), "quantity", String.valueOf(quantity)));
            List<Component> lore = new ArrayList<>();
            if (item.hasBuyPrice()) {
                lore.add(component(msg().guiBuyLine(), "price", formatPrice(shop, buyTotal(shop, item, quantity))));
            }
            if (item.hasSellPrice()) {
                lore.add(component(msg().guiSellLine(), "price", formatPrice(shop, sellTotal(shop, item, quantity))));
            }
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * Ikona presetu ilości = GUI-kopia skonfigurowanego shopitemu (materiał +
     * display-name + lore z shops.yml). Ilość presetu, „Wybrano" oraz limit
     * dzienny dopisujemy jako DODATKOWE linie lore — nigdy nie nadpisujemy
     * skonfigurowanych metadanych. Widoczny rozmiar stosu jest obcięty do
     * maxStackSize, ale pełna ilość zawsze widnieje w linii lore. Glint jest
     * tylko na kopii GUI; tradeUnit/exactTemplate i wydawane/sprzedawane itemy
     * pozostają nietknięte (listener blokuje wyjęcie ikony).
     */
    private ItemStack buildPresetButton(Shop shop, ShopItem item, int value, int selected, int buyRemaining) {
        boolean isSelected = value == selected;
        List<Component> extra = new ArrayList<>();
        // gui-preset-button używane jako linia ilości (nie jako nazwa).
        extra.add(component(msg().guiPresetButton(), "quantity", String.valueOf(value)));
        if (isSelected) {
            extra.add(component(msg().guiPresetSelected()));
        }
        if (item.hasBuyLimit() && value > buyRemaining) {
            extra.add(component(msg().guiBuyLimitLore(),
                    "limit", String.valueOf(item.maxBuyAmount()),
                    "remaining", String.valueOf(Math.max(0, buyRemaining))));
        }
        ItemStack stack = displayStack(item, extra);
        stack.setAmount(clampDisplayAmount(stack, value));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.values());
            if (isSelected) {
                // Dyskretny glint bez realnego enchanta — tylko na kopii GUI.
                // Kosmetyka: pomijamy, gdy platforma nie wspiera glint-override.
                try {
                    meta.setEnchantmentGlintOverride(true);
                } catch (Throwable ignored) {
                    // Zaznaczenie i tak niesie lore „Wybrano".
                }
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buildCustomQuantityButton() {
        ItemStack stack = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(msg().guiCustomQuantityButton()));
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buildBuyButton(Shop shop, ShopItem item, int quantity, int buyRemaining) {
        boolean overLimit = item.hasBuyLimit() && quantity > buyRemaining;
        Material mat = overLimit ? Material.BARRIER : Material.LIME_STAINED_GLASS_PANE;
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(msg().guiBuyButton(),
                    "quantity", String.valueOf(quantity),
                    "price", formatPrice(shop, buyTotal(shop, item, quantity))));
            List<Component> lore = new ArrayList<>();
            if (overLimit) {
                lore.add(component(msg().guiBuyLimitLore(),
                        "limit", String.valueOf(item.maxBuyAmount()),
                        "remaining", String.valueOf(Math.max(0, buyRemaining))));
            } else {
                lore.add(component(msg().guiClickToBuy()));
            }
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buildSellButton(Shop shop, ShopItem item, int quantity) {
        ItemStack stack = new ItemStack(Material.ORANGE_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(msg().guiSellButton(),
                    "quantity", String.valueOf(quantity),
                    "price", formatPrice(shop, sellTotal(shop, item, quantity))));
            meta.lore(List.of(component(msg().guiClickToSell())));
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buildSellAllButton(Shop shop, ShopItem item, SellAllQuote quote) {
        ItemStack stack = new ItemStack(Material.HOPPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(msg().guiSellAllButton()));
            List<Component> lore = new ArrayList<>();
            if (quote == null || !quote.hasItems()) {
                lore.add(component(msg().guiSellAllNone()));
            } else {
                lore.add(component(msg().guiSellAllOwned(), "amount", String.valueOf(quote.amount())));
                lore.add(component(msg().guiSellAllEarn(), "price", formatPrice(shop, quote.totalPrice())));
                lore.add(component(msg().guiClickToSell()));
            }
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buildBackButton() {
        // Aufgabe 5: przycisk „Wróć" w widoku szczegółów używa BARRIER.
        return namedItem(Material.BARRIER, msg().guiBackButton());
    }

    /** Prosty item z nazwą i ukrytymi flagami (dymek pozostaje widoczny). */
    private ItemStack namedItem(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(name));
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Ilość do wyświetlenia na ikonie (1..maxStackSize danego stosu). */
    private static int clampDisplayAmount(ItemStack stack, int quantity) {
        int max = Math.max(1, stack.getMaxStackSize());
        return Math.max(1, Math.min(quantity, max));
    }

    private ItemStack navButton(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(name));
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack pageInfoButton(int page, int totalPages) {
        ItemStack stack = new ItemStack(Material.BOOK);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(msg().guiPageInfo(),
                    "current", String.valueOf(page + 1),
                    "total", String.valueOf(totalPages)));
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private BigDecimal buyTotal(Shop shop, ShopItem item, int quantity) {
        return PriceCalculator.total(item.buyPrice(), item.amount(), quantity, priceScale(shop));
    }

    private BigDecimal sellTotal(Shop shop, ShopItem item, int quantity) {
        return PriceCalculator.total(item.sellPrice(), item.amount(), quantity, priceScale(shop));
    }

    /**
     * MONEY zachowuje dokładnie legacy formatting z HexNPC 1.0.0.
     * Dopiero jawny sklep HEX_COINS przechodzi przez multi-currency overload.
     */
    private String formatPrice(Shop shop, BigDecimal value) {
        if (shop == null || shop.currency() == ShopCurrency.MONEY) {
            return economy.format(value);
        }
        return economy.format(shop.currency(), value);
    }

    private int priceScale(Shop shop) {
        return shop != null && shop.currency() == ShopCurrency.HEX_COINS ? 0 : config.priceScale();
    }

    private static ItemStack hideFlags(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Tworzy komponent z legacy-tekstu, podstawiając pary {@code <klucz>->wartość}. */
    private static Component component(String template, String... replacements) {
        String out = template == null ? "" : template;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            out = out.replace("<" + replacements[i] + ">", replacements[i + 1]);
        }
        return LegacyFormat.component(out);
    }
}
