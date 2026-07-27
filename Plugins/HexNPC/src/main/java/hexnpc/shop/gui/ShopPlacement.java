package hexnpc.shop.gui;

import hexnpc.shop.model.PlacementMode;
import hexnpc.shop.model.Shop;
import hexnpc.shop.model.ShopItem;
import hexnpc.shop.model.ShopLayout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Czysta logika rozmieszczenia itemów sklepu na stronach widoku głównego.
 * Bezstanowa i deterministyczna — łatwa do testów jednostkowych.
 *
 * <ul>
 *   <li><b>AUTO</b> — itemy w kolejności z shops.yml wypełniają
 *       {@code layout.itemSlots()} i są dzielone na strony.</li>
 *   <li><b>MANUAL</b> — itemy trafiają na swoje {@code slot} w obrębie
 *       zadeklarowanej {@code page}; liczba stron wynika z najwyższej
 *       użytej strony.</li>
 * </ul>
 */
public final class ShopPlacement {

    private ShopPlacement() {
    }

    /** Liczba stron widoku głównego (zawsze >= 1). */
    public static int totalPages(Shop shop) {
        ShopLayout layout = shop.layout();
        List<ShopItem> items = shop.orderedItems();
        if (layout.placement() == PlacementMode.MANUAL) {
            int maxPage = 0;
            for (ShopItem item : items) {
                if (item.slot() >= 0 && item.slot() < layout.size()) {
                    maxPage = Math.max(maxPage, item.page());
                }
            }
            return maxPage + 1;
        }
        return layout.pageCount(items.size());
    }

    /** Ogranicza numer strony do poprawnego zakresu [0, totalPages-1]. */
    public static int clampPage(int page, int totalPages) {
        int max = Math.max(1, totalPages) - 1;
        if (page < 0) {
            return 0;
        }
        return Math.min(page, max);
    }

    /**
     * Zwraca mapę {slot GUI -> item} do wyrenderowania na danej stronie.
     * Kolejność wstawiania odzwierciedla kolejność itemów (AUTO) lub
     * kolejność deklaracji (MANUAL). Przy kolizji slotu w MANUAL wygrywa
     * pierwszy zadeklarowany item.
     */
    public static Map<Integer, ShopItem> itemsForPage(Shop shop, int page) {
        ShopLayout layout = shop.layout();
        List<ShopItem> items = shop.orderedItems();
        Map<Integer, ShopItem> result = new LinkedHashMap<>();
        if (layout.placement() == PlacementMode.MANUAL) {
            for (ShopItem item : items) {
                if (item.page() != page) {
                    continue;
                }
                int slot = item.slot();
                if (slot < 0 || slot >= layout.size()) {
                    continue;
                }
                result.putIfAbsent(slot, item);
            }
            return result;
        }
        // AUTO
        List<Integer> slots = layout.itemSlots();
        int perPage = Math.max(1, slots.size());
        int start = page * perPage;
        for (int i = 0; i < slots.size(); i++) {
            int idx = start + i;
            if (idx >= items.size()) {
                break;
            }
            result.put(slots.get(i), items.get(idx));
        }
        return result;
    }
}
