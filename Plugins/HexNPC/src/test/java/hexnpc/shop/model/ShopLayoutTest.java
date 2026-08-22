package hexnpc.shop.model;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walidacja i sanityzacja układu GUI: poprawne domyślne, wykrywanie kolizji
 * oraz slotów spoza zakresu (bezpieczne fallbacki, brak awarii).
 */
class ShopLayoutTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void defaultsAreValidForAllSizes() {
        for (int size = 9; size <= 54; size += 9) {
            ShopLayout layout = ShopLayout.defaults(size);
            assertEquals(size, layout.size());
            assertNoMainCollisions(layout);
            assertTrue(layout.itemsPerPage() >= 1);
            // Widok szczegółów wymaga 10 rozłącznych slotów; mieści się od 18
            // slotów w górę. GUI 9-slotowe jest zbyt małe i degraduje się
            // bezpiecznie (bez awarii), więc nie wymuszamy tam rozłączności.
            if (size >= 18) {
                assertNoDetailCollisions(layout);
            }
        }
    }

    @Test
    void invalidSizeFallsBackTo54() {
        assertEquals(54, ShopLayout.defaults(7).size());
        assertEquals(54, ShopLayout.defaults(100).size());
    }

    @Test
    void duplicateAndOutOfRangeItemSlotsAreDropped() {
        ShopLayout raw = new ShopLayout(27, PlacementMode.AUTO,
                List.of(10, 10, 12, 99, -3), 18, 22, 26,
                Material.GRAY_STAINED_GLASS_PANE, " ",
                0, 1, List.of(2, 3), 4, 5, 6, 7, 8);
        ShopLayout ok = raw.validated(null, "test");
        // Dwie unikalne, w zakresie wartości pozostają (10, 12); duplikaty i
        // wartości spoza zakresu są usunięte.
        assertTrue(ok.itemSlots().contains(10));
        assertTrue(ok.itemSlots().contains(12));
        assertFalse(ok.itemSlots().contains(99));
        assertFalse(ok.itemSlots().contains(-3));
        assertEquals(2, ok.itemSlots().size());
        assertNoMainCollisions(ok);
    }

    @Test
    void navigationCollisionWithItemsIsReassigned() {
        // page-slot 10 koliduje ze slotem itemu 10 -> sanityzacja przenosi.
        ShopLayout raw = new ShopLayout(27, PlacementMode.AUTO,
                List.of(10, 11, 12), 10, 10, 10,
                Material.GRAY_STAINED_GLASS_PANE, " ",
                0, 1, List.of(2, 3), 4, 5, 6, 7, 8);
        ShopLayout ok = raw.validated(null, "test");
        assertNoMainCollisions(ok);
    }

    @Test
    void outOfRangeDetailSlotsAreReassignedWithinRange() {
        ShopLayout raw = new ShopLayout(27, PlacementMode.AUTO,
                List.of(10, 11, 12), 18, 22, 26,
                Material.GRAY_STAINED_GLASS_PANE, " ",
                99, -1, List.of(50, 60), 70, 80, 90, 100, 110);
        ShopLayout ok = raw.validated(null, "test");
        assertNoDetailCollisions(ok);
        assertInRange(ok.detailPreviewSlot(), 27);
        assertInRange(ok.detailBuySlot(), 27);
        assertInRange(ok.detailSellSlot(), 27);
        assertInRange(ok.detailBackSlot(), 27);
    }

    @Test
    void default54IsAestheticSpacedGrid() {
        // Dokładnie żądany, symetryczny układ z odstępami (co druga kolumna).
        assertEquals(List.of(10, 12, 14, 16, 19, 21, 23, 25, 28, 30, 32, 34),
                ShopLayout.defaults(54).itemSlots());
        assertEquals(12, ShopLayout.defaults(54).itemsPerPage());
    }

    @Test
    void pageCountRoundsUp() {
        ShopLayout layout = ShopLayout.defaults(54); // 12 item-slots
        assertEquals(1, layout.pageCount(0));
        assertEquals(1, layout.pageCount(12));
        assertEquals(2, layout.pageCount(13));
        assertEquals(2, layout.pageCount(24));
        assertEquals(3, layout.pageCount(25));
    }

    // --- helpers ---

    private void assertNoMainCollisions(ShopLayout layout) {
        List<Integer> all = new ArrayList<>(layout.itemSlots());
        all.add(layout.previousSlot());
        all.add(layout.pageSlot());
        all.add(layout.nextSlot());
        assertDistinctInRange(all, layout.size(), "main");
    }

    private void assertNoDetailCollisions(ShopLayout layout) {
        List<Integer> all = new ArrayList<>();
        all.add(layout.detailPreviewSlot());
        all.add(layout.detailSelectedInfoSlot());
        all.addAll(layout.quantityPresetSlots());
        all.add(layout.detailCustomQuantitySlot());
        all.add(layout.detailBuySlot());
        all.add(layout.detailSellSlot());
        all.add(layout.detailSellAllSlot());
        all.add(layout.detailBackSlot());
        assertDistinctInRange(all, layout.size(), "detail");
    }

    private void assertDistinctInRange(List<Integer> slots, int size, String label) {
        Set<Integer> seen = new HashSet<>();
        for (int slot : slots) {
            assertInRange(slot, size);
            assertTrue(seen.add(slot), label + " ma kolidujący slot: " + slot);
        }
    }

    private void assertInRange(int slot, int size) {
        assertTrue(slot >= 0 && slot < size, "slot poza zakresem: " + slot + " (size=" + size + ")");
    }
}
