package hexnpc.shop.gui;

import hexnpc.shop.PriceCalculator;
import hexnpc.shop.config.ShopConfig;
import hexnpc.shop.config.ShopMessages;
import hexnpc.shop.economy.EconomyBridge;
import hexnpc.shop.inventory.ShopItemStackFactory;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import hexnpc.shop.model.ShopLayout;
import hexnpc.util.LegacyFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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

    public ShopGuiBuilder(ShopConfig config, EconomyBridge economy) {
        this.config = config;
        this.economy = economy;
    }

    private ShopMessages msg() {
        return config.messages();
    }

    // ================= Widok główny (MAIN) =================

    public Inventory buildMain(Shop shop, int page) {
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
            inv.setItem(e.getKey(), buildIcon(e.getValue()));
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

        inv.setItem(previewSlot, buildPreview(item, qty));
        inv.setItem(layout.detailSelectedInfoSlot(), buildSelectedInfo(item, qty));

        for (Map.Entry<Integer, Integer> entry : presetSlots.entrySet()) {
            inv.setItem(entry.getKey(), buildPresetButton(item, entry.getValue(), qty, buyRemaining));
        }
        if (customSlot >= 0) {
            inv.setItem(customSlot, buildCustomQuantityButton());
        }
        if (buySlot >= 0) {
            inv.setItem(buySlot, buildBuyButton(item, qty, buyRemaining));
        }
        if (sellSlot >= 0) {
            inv.setItem(sellSlot, buildSellButton(item, qty));
        }
        if (sellAllSlot >= 0) {
            inv.setItem(sellAllSlot, buildSellAllButton(item, sellAllQuote));
        }
        inv.setItem(backSlot, buildBackButton());
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
        lore.add(component(msg().guiConfirmPrice(), "price", economy.format(totalPrice)));
        ItemStack preview = ShopItemStackFactory.displayStack(item, lore);
        preview.setAmount(clampDisplayAmount(preview, quantity));
        inv.setItem(cfg.previewSlot(), hideFlags(preview));

        boolean buy = action == ConfirmAction.BUY;
        ItemStack confirm = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta cMeta = confirm.getItemMeta();
        if (cMeta != null) {
            cMeta.displayName(component(buy ? msg().guiConfirmBuy() : msg().guiConfirmSell()));
            cMeta.lore(List.of(
                    component(msg().guiConfirmQuantity(), "amount", String.valueOf(quantity)),
                    component(msg().guiConfirmPrice(), "price", economy.format(totalPrice))));
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

    private ItemStack buildIcon(ShopItem item) {
        List<Component> extra = new ArrayList<>();
        extra.add(Component.empty());
        if (item.hasBuyPrice()) {
            extra.add(component(msg().guiBuyLine(), "price", economy.format(item.buyPrice())));
        }
        if (item.hasSellPrice()) {
            extra.add(component(msg().guiSellLine(), "price", economy.format(item.sellPrice())));
        }
        extra.add(component(msg().guiClickForDetails()));
        ItemStack stack = ShopItemStackFactory.displayStack(item, extra);
        return hideFlags(stack);
    }

    private ItemStack buildPreview(ShopItem item, int quantity) {
        List<Component> extra = new ArrayList<>();
        extra.add(Component.empty());
        if (item.hasBuyPrice()) {
            String total = economy.format(buyTotal(item, quantity));
            extra.add(component(msg().guiBuyLine(), "price", total));
        }
        if (item.hasSellPrice()) {
            String total = economy.format(sellTotal(item, quantity));
            extra.add(component(msg().guiSellLine(), "price", total));
        }
        ItemStack stack = ShopItemStackFactory.displayStack(item, extra);
        return hideFlags(stack);
    }

    private ItemStack buildSelectedInfo(ShopItem item, int quantity) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(msg().guiSelectedQuantity(), "quantity", String.valueOf(quantity)));
            List<Component> lore = new ArrayList<>();
            if (item.hasBuyPrice()) {
                lore.add(component(msg().guiBuyLine(), "price", economy.format(buyTotal(item, quantity))));
            }
            if (item.hasSellPrice()) {
                lore.add(component(msg().guiSellLine(), "price", economy.format(sellTotal(item, quantity))));
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
    private ItemStack buildPresetButton(ShopItem item, int value, int selected, int buyRemaining) {
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
        ItemStack stack = ShopItemStackFactory.displayStack(item, extra);
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

    private ItemStack buildBuyButton(ShopItem item, int quantity, int buyRemaining) {
        boolean overLimit = item.hasBuyLimit() && quantity > buyRemaining;
        Material mat = overLimit ? Material.BARRIER : Material.LIME_STAINED_GLASS_PANE;
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(msg().guiBuyButton(),
                    "quantity", String.valueOf(quantity),
                    "price", economy.format(buyTotal(item, quantity))));
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

    private ItemStack buildSellButton(ShopItem item, int quantity) {
        ItemStack stack = new ItemStack(Material.ORANGE_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(msg().guiSellButton(),
                    "quantity", String.valueOf(quantity),
                    "price", economy.format(sellTotal(item, quantity))));
            meta.lore(List.of(component(msg().guiClickToSell())));
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack buildSellAllButton(ShopItem item, SellAllQuote quote) {
        ItemStack stack = new ItemStack(Material.HOPPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(component(msg().guiSellAllButton()));
            List<Component> lore = new ArrayList<>();
            if (quote == null || !quote.hasItems()) {
                lore.add(component(msg().guiSellAllNone()));
            } else {
                lore.add(component(msg().guiSellAllOwned(), "amount", String.valueOf(quote.amount())));
                lore.add(component(msg().guiSellAllEarn(), "price", economy.format(quote.totalPrice())));
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

    private BigDecimal buyTotal(ShopItem item, int quantity) {
        return PriceCalculator.total(item.buyPrice(), item.amount(), quantity, config.priceScale());
    }

    private BigDecimal sellTotal(ShopItem item, int quantity) {
        return PriceCalculator.total(item.sellPrice(), item.amount(), quantity, config.priceScale());
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
