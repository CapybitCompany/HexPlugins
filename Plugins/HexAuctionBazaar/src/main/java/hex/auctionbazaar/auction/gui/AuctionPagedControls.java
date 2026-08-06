package hex.auctionbazaar.auction.gui;

import hex.auctionbazaar.config.AuctionConfig;
import hex.auctionbazaar.gui.GuiFrame;
import hex.auctionbazaar.gui.GuiHolder;
import hex.auctionbazaar.util.MessageFactory;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.IntConsumer;

import static hex.auctionbazaar.util.MessageFactory.placeholders;

/**
 * Wspólny pasek nawigacji dla widoków stronicowanych Domu Aukcyjnego
 * (Odbiór przedmiotów / Moje aukcje). Sloty pochodzą z {@link AuctionConfig}
 * (zwalidowane, bez kolizji z obszarem przedmiotów 0..44).
 *
 * Zasady (punkt #7):
 *  - „Wróć” to zawsze BARRIER,
 *  - „poprzednia/następna” aktywne tylko gdy strona istnieje,
 *  - informacja o stronie pokazuje bieżącą stronę, liczbę stron i liczbę wpisów.
 */
final class AuctionPagedControls {

    private AuctionPagedControls() {
    }

    static void render(Inventory inv, GuiHolder holder, AuctionConfig cfg, MessageFactory messages,
                       int page, int totalPages, int total, String pageInfoKey,
                       IntConsumer openPage, Runnable back) {
        // Wróć (BARRIER) do głównego Domu Aukcyjnego.
        ItemStack backBtn = GuiFrame.button(Material.BARRIER,
                messages.raw("auction.gui.back", null),
                List.of(messages.raw("auction.gui.back-lore", null)));
        inv.setItem(cfg.pagedSlotBack(), backBtn);
        holder.setSlotAction(cfg.pagedSlotBack(), ctx -> back.run());

        boolean hasPrev = page > 0;
        boolean hasNext = (page + 1) < totalPages;
        ItemStack prev = GuiFrame.button(hasPrev ? Material.ARROW : Material.GRAY_DYE,
                messages.raw("auction.gui.prev-page", null));
        ItemStack next = GuiFrame.button(hasNext ? Material.ARROW : Material.GRAY_DYE,
                messages.raw("auction.gui.next-page", null));
        inv.setItem(cfg.pagedSlotPrevPage(), prev);
        inv.setItem(cfg.pagedSlotNextPage(), next);
        if (hasPrev) {
            holder.setSlotAction(cfg.pagedSlotPrevPage(), ctx -> openPage.accept(page - 1));
        }
        if (hasNext) {
            holder.setSlotAction(cfg.pagedSlotNextPage(), ctx -> openPage.accept(page + 1));
        }

        ItemStack info = GuiFrame.button(Material.PAPER,
                messages.raw(pageInfoKey, placeholders(
                        "page", String.valueOf(page + 1),
                        "total", String.valueOf(totalPages),
                        "count", String.valueOf(total))));
        inv.setItem(cfg.pagedSlotPageInfo(), info);
    }
}
