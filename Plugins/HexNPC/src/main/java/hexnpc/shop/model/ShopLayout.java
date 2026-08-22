package hexnpc.shop.model;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Konfigurowalny, symetryczny układ GUI sklepu — używany zarówno przez
 * widok główny (siatka itemów + paginacja), jak i widok szczegółów
 * (podgląd, wybór ilości, kup/sprzedaj/wróć).
 *
 * <p>Rekord jest niezmienny i zawsze poprawny: {@link #validated(Logger, String)}
 * sanityzuje wartości z konfiguracji — przycina sloty do rozmiaru GUI,
 * wykrywa kolizje i duplikaty, loguje czytelne ostrzeżenia i w razie
 * potrzeby podstawia bezpieczne wartości domyślne. Nieprawidłowa konfiguracja
 * nigdy nie powoduje wyjątku w runtime GUI.
 *
 * <p>Sloty widoku głównego (item-slots + paginacja) oraz sloty widoku
 * szczegółów są sanityzowane niezależnie — to dwa osobne inventory tego
 * samego rozmiaru.
 */
public record ShopLayout(
        int size,
        PlacementMode placement,
        List<Integer> itemSlots,
        int previousSlot,
        int pageSlot,
        int nextSlot,
        Material fillerMaterial,
        String fillerName,
        int detailPreviewSlot,
        int detailSelectedInfoSlot,
        List<Integer> quantityPresetSlots,
        int detailCustomQuantitySlot,
        int detailBuySlot,
        int detailSellSlot,
        int detailSellAllSlot,
        int detailBackSlot
) {

    public static final Material DEFAULT_FILLER = Material.GRAY_STAINED_GLASS_PANE;
    public static final String DEFAULT_FILLER_NAME = " ";

    public ShopLayout {
        if (size <= 0 || size % 9 != 0 || size > 54) {
            size = 54;
        }
        placement = placement == null ? PlacementMode.AUTO : placement;
        itemSlots = itemSlots == null ? List.of() : List.copyOf(itemSlots);
        quantityPresetSlots = quantityPresetSlots == null ? List.of() : List.copyOf(quantityPresetSlots);
        fillerMaterial = fillerMaterial == null || fillerMaterial.isAir() ? DEFAULT_FILLER : fillerMaterial;
        fillerName = fillerName == null ? DEFAULT_FILLER_NAME : fillerName;
    }

    /** Kopia z podmienionym trybem rozmieszczenia. */
    public ShopLayout withPlacement(PlacementMode newPlacement) {
        return new ShopLayout(size, newPlacement, itemSlots, previousSlot, pageSlot, nextSlot,
                fillerMaterial, fillerName, detailPreviewSlot, detailSelectedInfoSlot,
                quantityPresetSlots, detailCustomQuantitySlot, detailBuySlot, detailSellSlot,
                detailSellAllSlot, detailBackSlot);
    }

    /** Kopia z podmienionym slotem „Sprzedaj" (kompatybilność z legacy sell-slot). */
    public ShopLayout withDetailSellSlot(int newSellSlot) {
        return new ShopLayout(size, placement, itemSlots, previousSlot, pageSlot, nextSlot,
                fillerMaterial, fillerName, detailPreviewSlot, detailSelectedInfoSlot,
                quantityPresetSlots, detailCustomQuantitySlot, detailBuySlot, newSellSlot,
                detailSellAllSlot, detailBackSlot);
    }

    /** Ile itemów mieści się na jednej stronie widoku głównego. */
    public int itemsPerPage() {
        return Math.max(1, itemSlots.size());
    }

    /** Liczba stron dla podanej liczby itemów (minimum 1). */
    public int pageCount(int itemCount) {
        if (itemCount <= 0) {
            return 1;
        }
        int per = itemsPerPage();
        return Math.max(1, (itemCount + per - 1) / per);
    }

    /**
     * Buduje domyślny, symetryczny układ dla danego rozmiaru GUI z wyraźnymi
     * odstępami między itemami. Itemy stoją w co drugiej kolumnie (1,3,5,7),
     * dolny rząd jest zarezerwowany na nawigację. Dla 54 slotów daje to
     * dokładnie 12 pozycji na stronę: 10,12,14,16,19,21,23,25,28,30,32,34.
     */
    public static ShopLayout defaults(int size) {
        if (size <= 0 || size % 9 != 0 || size > 54) {
            size = 54;
        }
        int rows = size / 9;
        int navRow = rows - 1;

        List<Integer> items = new ArrayList<>();
        int prev;
        int page;
        int next;
        if (rows == 1) {
            // Jednorzędowe GUI: bezpieczna degradacja — itemy 0..5, nawigacja z prawej.
            for (int c = 0; c <= 5; c++) {
                items.add(c);
            }
            prev = 6;
            page = 7;
            next = 8;
        } else {
            // Co druga kolumna daje widoczne odstępy w poziomie.
            int[] cols = {1, 3, 5, 7};
            int startRow;
            int endRow;
            switch (rows) {
                case 2 -> { startRow = 0; endRow = 0; }   // 18: 4 itemy
                case 3 -> { startRow = 0; endRow = 1; }   // 27: 8 itemów
                case 4 -> { startRow = 1; endRow = 2; }   // 36: 8 itemów (górny margines)
                case 5 -> { startRow = 1; endRow = 3; }   // 45: 12 itemów
                default -> { startRow = 1; endRow = 3; }  // 54: 12 itemów (górny+dolny margines)
            }
            for (int r = startRow; r <= endRow; r++) {
                for (int c : cols) {
                    items.add(r * 9 + c);
                }
            }
            prev = navRow * 9;
            page = navRow * 9 + 4;
            next = navRow * 9 + 8;
        }

        // Widok szczegółów — wartości pożądane; sanityzacja zapewni rozłączność.
        int preview = 4;
        int selectedInfo = 13;
        int presetBase = rows >= 3 ? 18 : 0;
        List<Integer> presetSlots = new ArrayList<>(List.of(presetBase + 1, presetBase + 3, presetBase + 5));
        int customQty = presetBase + 7;
        int buy = rows >= 3 ? (navRow - 1) * 9 + 2 : size - 3;
        int sellAll = rows >= 3 ? (navRow - 1) * 9 + 4 : size - 2;
        int sell = rows >= 3 ? (navRow - 1) * 9 + 6 : size - 1;
        int back = size - 5;

        ShopLayout raw = new ShopLayout(size, PlacementMode.AUTO, items, prev, page, next,
                DEFAULT_FILLER, DEFAULT_FILLER_NAME, preview, selectedInfo, presetSlots,
                customQty, buy, sell, sellAll, back);
        return raw.validated(null, "default-" + size);
    }

    /**
     * Zwraca sklonowany układ, w którym wszystkie sloty są w zakresie
     * [0, size) i wzajemnie rozłączne w obrębie swojego widoku. Kolizje,
     * duplikaty i wartości poza zakresem są korygowane bezpiecznymi
     * fallbackami, a każda korekta jest logowana (jeśli podano logger).
     */
    public ShopLayout validated(Logger log, String ctx) {
        // --- Widok główny: item-slots + nawigacja ---
        Set<Integer> mainUsed = new LinkedHashSet<>();
        List<Integer> items = new ArrayList<>();
        for (int slot : itemSlots) {
            if (slot < 0 || slot >= size) {
                warn(log, ctx, "slot itemu poza zakresem (" + slot + "), pomijam");
                continue;
            }
            if (!mainUsed.add(slot)) {
                warn(log, ctx, "zduplikowany slot itemu (" + slot + "), pomijam");
                continue;
            }
            items.add(slot);
        }
        if (items.isEmpty()) {
            for (int slot : defaults(size).itemSlots()) {
                if (mainUsed.add(slot)) {
                    items.add(slot);
                }
            }
            warn(log, ctx, "brak poprawnych item-slots, użyto domyślnych");
        }

        int prev = claimMain(previousSlot, mainUsed, log, ctx, "slot poprzedniej strony");
        int page = claimMain(pageSlot, mainUsed, log, ctx, "slot numeru strony");
        int next = claimMain(nextSlot, mainUsed, log, ctx, "slot następnej strony");

        // --- Widok szczegółów: rozłączna pula slotów ---
        Set<Integer> detailUsed = new LinkedHashSet<>();
        int preview = claimDetail(detailPreviewSlot, detailUsed, log, ctx, "slot podglądu");
        int selectedInfo = claimDetail(detailSelectedInfoSlot, detailUsed, log, ctx, "slot informacji o ilości");
        List<Integer> presets = new ArrayList<>();
        for (int slot : quantityPresetSlots) {
            presets.add(claimDetail(slot, detailUsed, log, ctx, "slot presetu ilości"));
        }
        int customQty = claimDetail(detailCustomQuantitySlot, detailUsed, log, ctx, "slot własnej ilości");
        int buy = claimDetail(detailBuySlot, detailUsed, log, ctx, "slot kupna");
        int sell = claimDetail(detailSellSlot, detailUsed, log, ctx, "slot sprzedaży");
        int sellAll = claimDetail(detailSellAllSlot, detailUsed, log, ctx, "slot sprzedaży wszystkiego");
        int back = claimDetail(detailBackSlot, detailUsed, log, ctx, "slot powrotu");

        return new ShopLayout(size, placement, items, prev, page, next,
                fillerMaterial, fillerName, preview, selectedInfo, presets,
                customQty, buy, sell, sellAll, back);
    }

    private int claimMain(int desired, Set<Integer> used, Logger log, String ctx, String label) {
        if (desired < 0 || desired >= size) {
            int free = firstFree(used);
            warn(log, ctx, label + " poza zakresem (" + desired + "), użyto " + free);
            used.add(free);
            return free;
        }
        if (!used.add(desired)) {
            int free = firstFree(used);
            warn(log, ctx, label + " koliduje z innym slotem (" + desired + "), użyto " + free);
            used.add(free);
            return free;
        }
        return desired;
    }

    private int claimDetail(int desired, Set<Integer> used, Logger log, String ctx, String label) {
        if (desired < 0 || desired >= size) {
            int free = firstFree(used);
            warn(log, ctx, label + " poza zakresem (" + desired + "), użyto " + free);
            used.add(free);
            return free;
        }
        if (!used.add(desired)) {
            int free = firstFree(used);
            warn(log, ctx, label + " koliduje z innym slotem (" + desired + "), użyto " + free);
            used.add(free);
            return free;
        }
        return desired;
    }

    private int firstFree(Set<Integer> used) {
        for (int i = 0; i < size; i++) {
            if (!used.contains(i)) {
                return i;
            }
        }
        // GUI całkowicie zajęte — ostateczny fallback (nie powinno się zdarzyć).
        return size - 1;
    }

    private static void warn(Logger log, String ctx, String message) {
        if (log != null) {
            log.warning("HexNPC: układ sklepu [" + ctx + "]: " + message);
        }
    }
}
