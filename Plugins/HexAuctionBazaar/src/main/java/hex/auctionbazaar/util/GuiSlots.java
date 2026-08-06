package hex.auctionbazaar.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Czyste pomocnicze funkcje do walidacji slotów GUI (punkt #11):
 *  - sprawdzenie zakresu 0..size-1,
 *  - wykrywanie kolizji (ten sam slot użyty dwa razy),
 *  - sprawdzenie, że obszar przedmiotów i sloty nawigacji się nie nakładają.
 *
 * Dzięki temu nieprawidłowy layout można wykryć zanim zbudujemy inventory i
 * bezpiecznie wrócić do domyślnych wartości (z polskim ostrzeżeniem w logu).
 */
public final class GuiSlots {

    private GuiSlots() {
    }

    /** Wszystkie sloty w zakresie [0, size)? */
    public static boolean allInRange(int size, int... slots) {
        for (int s : slots) {
            if (s < 0 || s >= size) {
                return false;
            }
        }
        return true;
    }

    /** Czy zbiór slotów nie zawiera duplikatów? */
    public static boolean noDuplicates(int... slots) {
        Set<Integer> seen = new HashSet<>();
        for (int s : slots) {
            if (!seen.add(s)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Wykrywa kolizje między NAZWANYMI slotami kontrolnymi (mapa ścieżka-config -&gt; slot).
     * Zwraca po jednym opisie na kolizję, np. "auction.gui.slot-prev-page & auction.gui.slot-sort
     * -&gt; slot 50", aby ostrzeżenie wskazywało konkretne ścieżki i slot. Pusta lista = brak kolizji.
     * Dzięki temu żaden przycisk nie zostaje po cichu nadpisany przez inny.
     */
    public static List<String> findControlCollisions(Map<String, Integer> namedSlots) {
        List<String> collisions = new ArrayList<>();
        Map<Integer, String> seen = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : namedSlots.entrySet()) {
            String prev = seen.putIfAbsent(e.getValue(), e.getKey());
            if (prev != null) {
                collisions.add(prev + " & " + e.getKey() + " -> slot " + e.getValue());
            }
        }
        return collisions;
    }

    /**
     * Layout jest poprawny, gdy każdy slot nawigacji jest w zakresie, żaden nie
     * koliduje z innym slotem nawigacji i żaden nie wpada w obszar przedmiotów
     * [0, itemAreaSize).
     */
    public static boolean navLayoutValid(int size, int itemAreaSize, int... navSlots) {
        if (!allInRange(size, navSlots) || !noDuplicates(navSlots)) {
            return false;
        }
        for (int s : navSlots) {
            if (s < itemAreaSize) {
                return false;
            }
        }
        return true;
    }
}
