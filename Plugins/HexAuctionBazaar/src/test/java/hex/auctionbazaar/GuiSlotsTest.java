package hex.auctionbazaar;

import hex.auctionbazaar.util.GuiSlots;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #11: walidacja slotów GUI - zakres, brak duplikatów, brak kolizji z
 * obszarem przedmiotów. Nieprawidłowy layout musi być wykrywalny.
 */
class GuiSlotsTest {

    @Test
    void allInRangeChecksBounds() {
        assertTrue(GuiSlots.allInRange(54, 0, 45, 53));
        assertFalse(GuiSlots.allInRange(54, -1));
        assertFalse(GuiSlots.allInRange(54, 54));
    }

    @Test
    void noDuplicatesDetectsRepeat() {
        assertTrue(GuiSlots.noDuplicates(45, 48, 50, 49));
        assertFalse(GuiSlots.noDuplicates(45, 45));
        assertFalse(GuiSlots.noDuplicates(48, 50, 48));
    }

    @Test
    void navLayoutValidAcceptsDefault() {
        // Domyślne sloty stronicowania: back=45, prev=48, next=50, info=49 (obszar 0..44).
        assertTrue(GuiSlots.navLayoutValid(54, 45, 45, 48, 50, 49));
    }

    @Test
    void navLayoutRejectsCollisionInItemArea() {
        // Slot 10 wpada w obszar przedmiotów [0,45).
        assertFalse(GuiSlots.navLayoutValid(54, 45, 10, 48, 50, 49));
    }

    @Test
    void navLayoutRejectsDuplicateNavSlots() {
        assertFalse(GuiSlots.navLayoutValid(54, 45, 45, 45, 50, 49));
    }

    @Test
    void navLayoutRejectsOutOfRange() {
        assertFalse(GuiSlots.navLayoutValid(54, 45, 45, 48, 50, 54));
    }

    @Test
    void controlCollisionsPointToConcretePathsAndSlot() {
        var slots = new java.util.LinkedHashMap<String, Integer>();
        slots.put("auction.gui.slot-prev-page", 45);
        slots.put("auction.gui.slot-refresh", 49);
        slots.put("auction.gui.slot-sort", 45);   // kolizja z prev-page
        var cols = GuiSlots.findControlCollisions(slots);
        assertTrue(cols.size() == 1, "jedna kolizja");
        assertTrue(cols.get(0).contains("slot 45"));
        assertTrue(cols.get(0).contains("slot-prev-page") && cols.get(0).contains("slot-sort"));
    }

    @Test
    void noControlCollisionsWhenAllDistinct() {
        var slots = new java.util.LinkedHashMap<String, Integer>();
        slots.put("a", 45);
        slots.put("b", 49);
        slots.put("c", 50);
        assertTrue(GuiSlots.findControlCollisions(slots).isEmpty());
    }
}
