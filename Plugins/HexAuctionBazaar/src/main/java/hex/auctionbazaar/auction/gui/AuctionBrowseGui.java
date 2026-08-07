package hex.auctionbazaar.auction.gui;

import hex.auctionbazaar.auction.model.AuctionListing;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.bridge.EconomyBridge;
import hex.auctionbazaar.config.AuctionConfig;
import hex.auctionbazaar.gui.GuiFrame;
import hex.auctionbazaar.gui.GuiHolder;
import hex.auctionbazaar.util.ItemSerializer;
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

/**
 * Glowne GUI Domu Aukcyjnego (6 rzedow, 54 sloty).
 * Zawsze wyswietla ramke oraz przyciski nawigacyjne - takze gdy nie ma
 * zadnych aktywnych aukcji (empty-state). Numery slotow oraz material
 * ramki sa konfigurowalne (patrz {@link AuctionConfig}).
 */
public final class AuctionBrowseGui {

    public static void open(Plugin plugin, Player player,
                            Supplier<AuctionConfig> cfg,
                            AuctionService service,
                            EconomyBridge economy,
                            MessageFactory messages) {
        open(plugin, player, cfg, service, economy, messages, 0, AuctionService.SortMode.NEWEST);
    }

    public static void open(Plugin plugin, Player player,
                            Supplier<AuctionConfig> cfg,
                            AuctionService service,
                            EconomyBridge economy,
                            MessageFactory messages,
                            int page,
                            AuctionService.SortMode sort) {
        int capacity = cfg.get().itemSlots().size();
        int safePage = Math.max(0, page);
        int offset = AuctionItemArea.offset(safePage, capacity);
        service.listActive(capacity, offset, sort).thenAcceptBoth(
                service.countActive(),
                (listings, total) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    // Bezpieczne KLEMOWANIE strony: po zakupie/anulowaniu/wyścigu strona mogła wyjść
                    // poza zakres. Klemujemy do [0, totalPages-1] i - jeśli przeskoczyliśmy - otwieramy
                    // raz ponownie na dozwolonej stronie (bez „Strona 6/1", bez fałszywego pustego stanu).
                    int totalPages = AuctionItemArea.totalPages(total, capacity);
                    int clamped = total > 0 ? AuctionItemArea.clampPage(safePage, totalPages) : 0;
                    if (total > 0 && clamped != safePage) {
                        open(plugin, player, cfg, service, economy, messages, clamped, sort);
                        return;
                    }
                    render(plugin, player, listings, total, cfg.get(),
                            service, economy, messages, clamped, sort);
                })
        );
    }

    private static void render(Plugin plugin, Player player, List<AuctionListing> listings, int total,
                                AuctionConfig cfg, AuctionService service, EconomyBridge economy,
                                MessageFactory messages, int page, AuctionService.SortMode sort) {
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.AUCTION_BROWSE);
        Inventory inv = Bukkit.createInventory(holder, 54, LegacyFormat.component(cfg.guiTitle()));
        holder.bindInventory(inv);

        List<Integer> itemSlots = cfg.itemSlots();
        int capacity = itemSlots.size();
        for (int i = 0; i < listings.size() && i < capacity; i++) {
            AuctionListing l = listings.get(i);
            int slot = itemSlots.get(i);
            ItemStack icon = ItemSerializer.deserialize(l.itemBlob());
            if (icon == null) {
                icon = new ItemStack(Material.BARRIER);
            }
            boolean own = l.sellerUuid().equals(player.getUniqueId());
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>();
                // Wszystkie linie lore pobierane sa z messages.yml aby zachowac Polski jezyk.
                lore.add(LegacyFormat.component(messages.raw("auction.gui.listing-seller",
                        placeholders("seller", l.sellerName() == null ? "?" : l.sellerName()))));
                lore.add(LegacyFormat.component(messages.raw("auction.gui.listing-price",
                        placeholders("price", economy.format(l.price())))));
                lore.add(Component.empty());
                if (own) {
                    // Własna aukcja jest wizualnie oznaczona i zablokowana do kupna.
                    lore.add(LegacyFormat.component(messages.raw("auction.gui.listing-own", null)));
                    lore.add(LegacyFormat.component(messages.raw("auction.gui.listing-own-hint", null)));
                } else {
                    lore.add(LegacyFormat.component(messages.raw("auction.gui.listing-click-buy", null)));
                }
                meta.lore(lore);
                icon.setItemMeta(meta);
            }
            inv.setItem(slot, icon);
            final long listingId = l.id();
            if (own) {
                // Klik na własnej aukcji nie otwiera okna zakupu - tylko komunikat.
                holder.setSlotAction(slot, ctx -> messages.send(ctx.player(), "auction.own-listing"));
            } else {
                holder.setSlotAction(slot, ctx -> AuctionConfirmGui.open(plugin, ctx.player(), listingId,
                        () -> cfg, service, economy, messages));
            }
        }

        if (listings.isEmpty()) {
            inv.setItem(cfg.slotEmptyState(), emptyStateItem(messages));
        }

        int totalPages = AuctionItemArea.totalPages(total, capacity);
        int currentPage = page + 1;
        addControls(inv, holder, plugin, cfg, service, economy, messages,
                page, sort, currentPage, totalPages, total);

        Material frame = GuiFrame.materialOrDefault(cfg.frameMaterial(), Material.BLACK_STAINED_GLASS_PANE);
        GuiFrame.fillEmpty(inv, frame);

        player.openInventory(inv);
    }

    private static ItemStack emptyStateItem(MessageFactory messages) {
        List<String> lore = List.of(
                messages.raw("auction.empty-state-lore-1", null),
                messages.raw("auction.empty-state-lore-2", null),
                "",
                messages.raw("auction.empty-state-lore-3", null)
        );
        return GuiFrame.button(Material.BARRIER,
                messages.raw("auction.empty-state-title", null), lore);
    }

    private static void addControls(Inventory inv, GuiHolder holder, Plugin plugin,
                                    AuctionConfig cfg, AuctionService service, EconomyBridge economy,
                                    MessageFactory messages, int page, AuctionService.SortMode sort,
                                    int currentPage, int totalPages, int total) {
        // Refresh
        ItemStack refresh = GuiFrame.button(Material.CLOCK,
                messages.raw("auction.gui.refresh", null),
                List.of(messages.raw("auction.gui.page-info",
                        placeholders("page", String.valueOf(currentPage),
                                "total", String.valueOf(totalPages),
                                "count", String.valueOf(total)))));
        inv.setItem(cfg.slotRefresh(), refresh);
        holder.setSlotAction(cfg.slotRefresh(),
                ctx -> open(plugin, ctx.player(), () -> cfg, service, economy, messages, page, sort));

        // Prev / Next
        boolean hasPrev = page > 0;
        boolean hasNext = (page + 1) < totalPages;
        ItemStack prev = GuiFrame.button(hasPrev ? Material.ARROW : Material.GRAY_DYE,
                messages.raw("auction.gui.prev-page", null));
        ItemStack next = GuiFrame.button(hasNext ? Material.ARROW : Material.GRAY_DYE,
                messages.raw("auction.gui.next-page", null));
        inv.setItem(cfg.slotPrevPage(), prev);
        inv.setItem(cfg.slotNextPage(), next);
        if (hasPrev) {
            holder.setSlotAction(cfg.slotPrevPage(),
                    ctx -> open(plugin, ctx.player(), () -> cfg, service, economy, messages, page - 1, sort));
        }
        if (hasNext) {
            holder.setSlotAction(cfg.slotNextPage(),
                    ctx -> open(plugin, ctx.player(), () -> cfg, service, economy, messages, page + 1, sort));
        }

        // Moje aukcje
        ItemStack mine = GuiFrame.button(Material.WRITABLE_BOOK,
                messages.raw("auction.gui.my-listings", null),
                List.of(messages.raw("auction.gui.my-listings-lore", null)));
        inv.setItem(cfg.slotMyListings(), mine);
        holder.setSlotAction(cfg.slotMyListings(),
                ctx -> AuctionMyListingsGui.open(plugin, ctx.player(), () -> cfg, service, economy, messages,
                        0, page, sort));

        // Odbior nagrod
        ItemStack claims = GuiFrame.button(Material.CHEST,
                messages.raw("auction.gui.claims", null),
                List.of(messages.raw("auction.gui.claims-lore", null)));
        inv.setItem(cfg.slotClaims(), claims);
        holder.setSlotAction(cfg.slotClaims(),
                ctx -> AuctionClaimsGui.open(plugin, ctx.player(), () -> cfg, service, economy, messages,
                        0, page, sort));

        // Wystaw przedmiot - otwiera dedykowane GUI wystawiania.
        ItemStack sellHelp = GuiFrame.button(Material.EMERALD,
                messages.raw("auction.gui.sell-help", null),
                List.of(messages.raw("auction.gui.sell-help-lore-1", null),
                        messages.raw("auction.gui.sell-help-lore-2", null)));
        inv.setItem(cfg.slotSellHelp(), sellHelp);
        holder.setSlotAction(cfg.slotSellHelp(),
                ctx -> AuctionSellGui.open(plugin, ctx.player(), () -> cfg, service, economy, messages));

        // Sortowanie
        String modeLabel = switch (sort) {
            case PRICE_ASC -> messages.raw("auction.gui.sort-price-asc", null);
            case PRICE_DESC -> messages.raw("auction.gui.sort-price-desc", null);
            default -> messages.raw("auction.gui.sort-newest", null);
        };
        ItemStack sortBtn = GuiFrame.button(Material.HOPPER,
                messages.raw("auction.gui.sort", placeholders("mode", modeLabel)),
                List.of(messages.raw("auction.gui.sort-lore", null)));
        inv.setItem(cfg.slotSort(), sortBtn);
        holder.setSlotAction(cfg.slotSort(), ctx -> {
            AuctionService.SortMode next2 = switch (sort) {
                case NEWEST -> AuctionService.SortMode.PRICE_ASC;
                case PRICE_ASC -> AuctionService.SortMode.PRICE_DESC;
                case PRICE_DESC -> AuctionService.SortMode.NEWEST;
            };
            open(plugin, ctx.player(), () -> cfg, service, economy, messages, 0, next2);
        });
    }
}
