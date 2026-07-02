package hex.auctionbazaar.bazaar.gui;

import hex.auctionbazaar.HexAuctionBazaarPlugin;
import hex.auctionbazaar.bazaar.service.BazaarService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.config.BazaarItemConfig;
import hex.auctionbazaar.gui.GuiFrame;
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
import java.util.Map;
import java.util.function.Supplier;

import static hex.auctionbazaar.util.MessageFactory.placeholders;

/**
 * Główne GUI Bazaru:
 *  - lewa kolumna (0, 9, 18, 27, 36): przyciski kategorii (5 slotow),
 *  - centralny obszar: przedmioty z aktualnej kategorii, ulozone
 *    centralnie w rzedach 12-16, 21-25, 30-34 (mieszcza sie 15 przedmiotow bez paginacji),
 *  - jesli przedmiotow wiecej niz 15 - paginacja przez strzalki w dolnym rzedzie,
 *  - dolny wiersz: moje zlecenia + odswiez + zamknij + prev/next.
 * Uzywa jednego zapytania DB (marketSnapshot) - bez N+1.
 * Auto-refresh: jesli wlaczony w configu, GUI odswieza sie co N tickow
 * dopoki jest otwarte (patrz {@link BazaarAutoRefreshTicker}).
 */
public final class BazaarMainGui {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    // Dolny wiersz kontrolek.
    private static final int SLOT_PREV_PAGE = 45;
    private static final int SLOT_ORDERS = 48;
    private static final int SLOT_REFRESH = 49;
    private static final int SLOT_CLOSE = 50;
    private static final int SLOT_NEXT_PAGE = 53;
    // Lewa kolumna dla kategorii.
    private static final int[] CATEGORY_SLOTS = {0, 9, 18, 27, 36};
    // Centralne sloty pod przedmioty (rzedy 12-16, 21-25, 30-34).
    private static final int[] ITEM_SLOTS = {
            12, 13, 14, 15, 16,
            21, 22, 23, 24, 25,
            30, 31, 32, 33, 34
    };

    public static void open(Plugin plugin, Player player, Supplier<BazaarConfig> cfg,
                            BazaarService service, EconomyBridge economy, MessageFactory messages) {
        String defaultCat = pickDefaultCategory(cfg.get());
        open(plugin, player, cfg, service, economy, messages, defaultCat, 0);
    }

    public static void open(Plugin plugin, Player player, Supplier<BazaarConfig> cfg,
                            BazaarService service, EconomyBridge economy, MessageFactory messages,
                            String activeCategory, int page) {
        String cat = activeCategory == null ? pickDefaultCategory(cfg.get()) : activeCategory;
        service.marketSnapshot().thenAccept(snapshot ->
                Bukkit.getScheduler().runTask(plugin,
                        () -> render(plugin, player, cfg.get(), snapshot, service, economy, messages,
                                cat, Math.max(0, page)))
        );
    }

    private static String pickDefaultCategory(BazaarConfig cfg) {
        // Pierwsza dostepna kategoria (nie ma juz "all").
        return cfg.categories().values().stream()
                .findFirst()
                .map(BazaarConfig.CategoryConfig::key)
                .orElse("blocks");
    }

    private static void render(Plugin plugin, Player player, BazaarConfig cfg,
                               Map<String, BazaarService.Snapshot> snapshot,
                               BazaarService service, EconomyBridge economy, MessageFactory messages,
                               String activeCategory, int page) {
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.BAZAAR_MAIN);
        Inventory inv = Bukkit.createInventory(holder, SIZE, LegacyFormat.component(cfg.guiTitle()));
        holder.bindInventory(inv);
        holder.putState("category", activeCategory);
        holder.putState("page", page);

        renderCategories(inv, holder, plugin, player, cfg, service, economy, messages, activeCategory);
        int totalMatching = countCategoryItems(cfg, activeCategory);
        int totalPages = Math.max(1, (int) Math.ceil(totalMatching / (double) ITEM_SLOTS.length));
        int safePage = Math.min(page, totalPages - 1);
        renderItems(inv, holder, plugin, cfg, snapshot, economy, messages, activeCategory, service, safePage);
        renderControls(inv, holder, plugin, player, cfg, service, economy, messages,
                activeCategory, safePage, totalPages);
        GuiFrame.fillEmpty(inv, GuiFrame.materialOrDefault(cfg.frameMaterial(), Material.GRAY_STAINED_GLASS_PANE));

        player.openInventory(inv);

        // Auto-refresh: kazde otwarcie glownego GUI rejestruje sie w tickerze,
        // ticker sam wyrejestruje sesje gdy inventory sie zamknie.
        HexAuctionBazaarPlugin main = plugin instanceof HexAuctionBazaarPlugin p ? p : null;
        if (main != null && main.autoRefreshTicker() != null) {
            main.autoRefreshTicker().register(player, GuiHolder.Kind.BAZAAR_MAIN,
                    () -> open(plugin, player, () -> cfg, service, economy, messages,
                            activeCategory, safePage));
        }
    }

    private static int countCategoryItems(BazaarConfig cfg, String activeCategory) {
        int c = 0;
        for (BazaarItemConfig item : cfg.items().values()) {
            if (item.category().equalsIgnoreCase(activeCategory)) c++;
        }
        return c;
    }

    private static void renderCategories(Inventory inv, GuiHolder holder, Plugin plugin, Player player,
                                          BazaarConfig cfg, BazaarService service, EconomyBridge economy,
                                          MessageFactory messages, String activeCategory) {
        List<BazaarConfig.CategoryConfig> categories = new ArrayList<>(cfg.categories().values());
        for (int i = 0; i < CATEGORY_SLOTS.length && i < categories.size(); i++) {
            int slot = CATEGORY_SLOTS[i];
            BazaarConfig.CategoryConfig cat = categories.get(i);
            Material mat = GuiFrame.materialOrDefault(cat.material(), Material.CHEST);
            boolean isActive = cat.key().equalsIgnoreCase(activeCategory);
            String name = cat.displayName() + (isActive ? " &7(wybrana)" : "");
            ItemStack icon = GuiFrame.button(mat, name,
                    List.of(messages.raw("bazaar.gui.lore-click", null)));
            inv.setItem(slot, icon);
            final String catKey = cat.key();
            holder.setSlotAction(slot, ctx -> open(plugin, ctx.player(),
                    () -> cfg, service, economy, messages, catKey, 0));
        }
    }

    private static void renderItems(Inventory inv, GuiHolder holder, Plugin plugin, BazaarConfig cfg,
                                     Map<String, BazaarService.Snapshot> snapshot,
                                     EconomyBridge economy, MessageFactory messages,
                                     String activeCategory, BazaarService service, int page) {
        int startIdx = page * ITEM_SLOTS.length;
        int cursor = 0;
        int categoryIdx = 0;
        int rendered = 0;
        for (BazaarItemConfig item : cfg.items().values()) {
            if (!item.category().equalsIgnoreCase(activeCategory)) continue;
            if (categoryIdx++ < startIdx) continue;
            if (cursor >= ITEM_SLOTS.length) break;
            int slot = ITEM_SLOTS[cursor++];
            BazaarService.Snapshot snap = snapshot.get(item.key());
            inv.setItem(slot, buildItemIcon(item, snap, economy, messages));
            final String key = item.key();
            holder.setSlotAction(slot, ctx -> BazaarItemGui.open(plugin, ctx.player(), key,
                    () -> cfg, service, economy, messages));
            rendered++;
        }
        if (rendered == 0) {
            inv.setItem(ITEM_SLOTS[ITEM_SLOTS.length / 2], GuiFrame.button(Material.BARRIER,
                    messages.raw("bazaar.gui.empty-category-title", null),
                    List.of(messages.raw("bazaar.gui.empty-category-lore", null))));
        }
    }

    private static ItemStack buildItemIcon(BazaarItemConfig item, BazaarService.Snapshot snap,
                                            EconomyBridge economy, MessageFactory messages) {
        ItemStack icon = new ItemStack(item.material());
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyFormat.component(item.displayName()));
            List<Component> lore = new ArrayList<>();
            if (snap != null && snap.price() != null) {
                lore.add(LegacyFormat.component(messages.raw("bazaar.gui.lore-buy",
                        placeholders("price", economy.format(snap.price().buyPrice())))));
                lore.add(LegacyFormat.component(messages.raw("bazaar.gui.lore-sell",
                        placeholders("price", economy.format(snap.price().sellPrice())))));
                lore.add(LegacyFormat.component(messages.raw("bazaar.gui.lore-stock",
                        placeholders("stock", String.valueOf(snap.stock())))));
                lore.add(LegacyFormat.component(messages.raw("bazaar.gui.lore-spread",
                        placeholders("spread", snap.spread().toPlainString()))));
            }
            lore.add(LegacyFormat.component(messages.raw("bazaar.gui.lore-category",
                    placeholders("category", item.category()))));
            lore.add(Component.empty());
            lore.add(LegacyFormat.component(messages.raw("bazaar.gui.lore-click", null)));
            meta.lore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static void renderControls(Inventory inv, GuiHolder holder, Plugin plugin, Player player,
                                        BazaarConfig cfg, BazaarService service, EconomyBridge economy,
                                        MessageFactory messages, String activeCategory,
                                        int page, int totalPages) {
        ItemStack orders = GuiFrame.button(Material.WRITABLE_BOOK,
                messages.raw("bazaar.gui.orders", null),
                List.of(messages.raw("bazaar.gui.orders-lore", null)));
        inv.setItem(SLOT_ORDERS, orders);
        holder.setSlotAction(SLOT_ORDERS, ctx -> BazaarOrdersGui.open(plugin, ctx.player(),
                () -> cfg, service, economy, messages));

        ItemStack refresh = GuiFrame.button(Material.CLOCK,
                messages.raw("bazaar.gui.refresh", null));
        inv.setItem(SLOT_REFRESH, refresh);
        holder.setSlotAction(SLOT_REFRESH,
                ctx -> open(plugin, ctx.player(), () -> cfg, service, economy, messages, activeCategory, page));

        ItemStack close = GuiFrame.button(Material.BARRIER,
                messages.raw("bazaar.gui.close", null));
        inv.setItem(SLOT_CLOSE, close);
        holder.setSlotAction(SLOT_CLOSE, ctx -> ctx.player().closeInventory());

        boolean hasPrev = page > 0;
        boolean hasNext = (page + 1) < totalPages;
        ItemStack prev = GuiFrame.button(hasPrev ? Material.ARROW : Material.GRAY_DYE,
                messages.raw("auction.gui.prev-page", null));
        ItemStack next = GuiFrame.button(hasNext ? Material.ARROW : Material.GRAY_DYE,
                messages.raw("auction.gui.next-page", null));
        inv.setItem(SLOT_PREV_PAGE, prev);
        inv.setItem(SLOT_NEXT_PAGE, next);
        if (hasPrev) {
            holder.setSlotAction(SLOT_PREV_PAGE,
                    ctx -> open(plugin, ctx.player(), () -> cfg, service, economy, messages,
                            activeCategory, page - 1));
        }
        if (hasNext) {
            holder.setSlotAction(SLOT_NEXT_PAGE,
                    ctx -> open(plugin, ctx.player(), () -> cfg, service, economy, messages,
                            activeCategory, page + 1));
        }
    }
}
