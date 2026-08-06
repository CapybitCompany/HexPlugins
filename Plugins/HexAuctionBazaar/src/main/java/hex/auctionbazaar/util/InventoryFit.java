package hex.auctionbazaar.util;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;
import java.util.Map;

/**
 * Bezpieczne, all-or-nothing dokładanie przedmiotu do ekwipunku: snapshot slotów
 * schowka, {@code addItem()}, a przy przepełnieniu przywrócenie snapshotu - tak,
 * że w ekwipunku nie zostaje żaden częściowy stack.
 *
 * Dostęp do ekwipunku jest odseparowany przez {@link InventoryAccess}, dzięki czemu
 * logikę można testować bez serwera. Każdy wyjątek (snapshot/add/revert) daje
 * {@link Result#STATE_UNCERTAIN} i NIGDY nie wydostaje się z metody - dzięki czemu
 * caller może domknąć swój future zamiast wisieć, a przy niepewnym stanie nie
 * zapisuje przedmiotu jako claim (ryzyko duplikacji).
 */
public final class InventoryFit {

    /** Wynik próby all-or-nothing. Trzy stany, które NIE mogą się zlewać. */
    public enum Result {
        /** Cały przedmiot został dodany do ekwipunku. */
        ADDED_FULLY,
        /** Nie zmieścił się w całości; snapshot został poprawnie przywrócony (nic nie zostało). */
        NOT_FIT_REVERTED,
        /**
         * Snapshot/add/revert rzuciło albo zwróciło {@code null} - rzeczywistego stanu ekwipunku nie
         * da się bezpiecznie ustalić. Przy tym wyniku NIE wolno: tworzyć item-claim, zwracać pełnych
         * pieniędzy ani cofać claim-a (część przedmiotu mogła już trafić do ekwipunku -> ryzyko
         * duplikacji/nadkompensacji). Rozstrzygnięcie (log SEVERE + ręczna korekta) należy do wołającego.
         */
        STATE_UNCERTAIN
    }

    /** Odseparowany dostęp do schowka ekwipunku (produkcja: {@link #playerAccess}; testy: atrapa). */
    public interface InventoryAccess {
        /** Głęboka kopia wszystkich slotów schowka. */
        ItemStack[] snapshotStorage();

        /** Dodaje przedmiot; zwraca resztę (leftover) tak jak Bukkit {@code addItem}. */
        Map<Integer, ItemStack> addItem(ItemStack stack);

        /** Przywraca dokładnie podany snapshot schowka. */
        void restoreStorage(ItemStack[] snapshot);
    }

    private InventoryFit() {
    }

    /**
     * All-or-nothing próba dodania {@code stack}. Wszystkie dostępy do ekwipunku muszą
     * odbywać się na wątku głównym (odpowiada za to caller). Nigdy nie rzuca.
     */
    public static Result tryAddFull(InventoryAccess inv, ItemStack stack) {
        if (inv == null || stack == null || stack.getAmount() <= 0) {
            return Result.STATE_UNCERTAIN;
        }
        ItemStack[] snapshot;
        try {
            snapshot = inv.snapshotStorage();
        } catch (Throwable t) {
            return Result.STATE_UNCERTAIN;
        }
        if (snapshot == null) {
            // Bez wiarygodnego snapshotu nie da się bezpiecznie cofnąć -> nie ryzykujemy.
            return Result.STATE_UNCERTAIN;
        }
        Map<Integer, ItemStack> leftover;
        try {
            leftover = inv.addItem(stack.clone());
        } catch (Throwable t) {
            // addItem mogło już częściowo zmienić ekwipunek -> stan niepewny (bez claim, bez duplikacji).
            return Result.STATE_UNCERTAIN;
        }
        if (leftover == null) {
            // Nieznana reszta -> nie wiadomo, ile trafiło do ekwipunku. Traktuj jako NIEPEWNE (nie jako pełne).
            return Result.STATE_UNCERTAIN;
        }
        if (leftover.isEmpty()) {
            return Result.ADDED_FULLY;                 // pusta, NIE-null reszta = całość dodana
        }
        try {
            inv.restoreStorage(snapshot);
        } catch (Throwable t) {
            return Result.STATE_UNCERTAIN;
        }
        return Result.NOT_FIT_REVERTED;                // przepełnienie + potwierdzony revert
    }

    /**
     * Batch all-or-nothing (punkt #1): dodaje WSZYSTKIE podane stacki albo NIC. Robimy JEDEN głęboki
     * snapshot schowka, próbujemy dodać każdy stack po kolei i - jeśli którykolwiek się nie zmieści -
     * przywracamy dokładnie snapshot (w ekwipunku nie zostaje ŻADEN częściowy stack). Zwraca ten sam
     * tri-state co {@link #tryAddFull}:
     *  - {@link Result#ADDED_FULLY}: wszystkie stacki zmieściły się w całości;
     *  - {@link Result#NOT_FIT_REVERTED}: co najmniej jeden się nie zmieścił, snapshot przywrócony;
     *  - {@link Result#STATE_UNCERTAIN}: snapshot/add/revert rzuciło albo {@code addItem} zwróciło
     *    {@code null} - rzeczywistego stanu nie da się bezpiecznie ustalić (część mogła już trafić do
     *    ekwipunku -> wołający NIE tworzy claim-ów, by uniknąć duplikacji).
     * Nigdy nie rzuca. Dostęp do ekwipunku wyłącznie na wątku głównym (odpowiada za to caller).
     */
    public static Result tryAddAllFull(InventoryAccess inv, List<ItemStack> stacks) {
        if (inv == null || stacks == null) {
            return Result.STATE_UNCERTAIN;
        }
        if (stacks.isEmpty()) {
            return Result.ADDED_FULLY;
        }
        for (ItemStack s : stacks) {
            if (s == null || s.getAmount() <= 0) {
                return Result.STATE_UNCERTAIN;
            }
        }
        ItemStack[] snapshot;
        try {
            snapshot = inv.snapshotStorage();
        } catch (Throwable t) {
            return Result.STATE_UNCERTAIN;
        }
        if (snapshot == null) {
            // Bez wiarygodnego snapshotu nie da się bezpiecznie cofnąć -> nie ryzykujemy.
            return Result.STATE_UNCERTAIN;
        }
        boolean allFit = true;
        for (ItemStack s : stacks) {
            Map<Integer, ItemStack> leftover;
            try {
                leftover = inv.addItem(s.clone());
            } catch (Throwable t) {
                // addItem mogło już częściowo zmienić ekwipunek -> stan niepewny (bez claim, bez duplikacji).
                return Result.STATE_UNCERTAIN;
            }
            if (leftover == null) {
                // Nieznana reszta -> nie wiadomo, ile trafiło do ekwipunku -> NIEPEWNE.
                return Result.STATE_UNCERTAIN;
            }
            if (!leftover.isEmpty()) {
                allFit = false;
                break;    // co najmniej jeden się nie zmieścił -> przywróć CAŁY snapshot (all-or-nothing)
            }
        }
        if (allFit) {
            return Result.ADDED_FULLY;
        }
        try {
            inv.restoreStorage(snapshot);
        } catch (Throwable t) {
            return Result.STATE_UNCERTAIN;
        }
        return Result.NOT_FIT_REVERTED;
    }

    /**
     * Produkcyjny wariant batch dla wątku głównego (Player). Chroniony - {@code null}/nieprawidłowy
     * gracz lub błąd adaptera dają {@link Result#STATE_UNCERTAIN}. Nigdy nie rzuca.
     */
    public static Result tryAddAllFull(Player player, List<ItemStack> stacks) {
        if (player == null || stacks == null) {
            return Result.STATE_UNCERTAIN;
        }
        InventoryAccess access;
        try {
            access = playerAccess(player);
        } catch (Throwable t) {
            return Result.STATE_UNCERTAIN;
        }
        return tryAddAllFull(access, stacks);
    }

    /** Produkcyjny adapter opakowujący {@link PlayerInventory}. */
    public static InventoryAccess playerAccess(Player player) {
        final PlayerInventory inv = player.getInventory();
        return new InventoryAccess() {
            @Override
            public ItemStack[] snapshotStorage() {
                return deepCopy(inv.getStorageContents());
            }

            @Override
            public Map<Integer, ItemStack> addItem(ItemStack stack) {
                return inv.addItem(stack);
            }

            @Override
            public void restoreStorage(ItemStack[] snapshot) {
                // Zapis per-slot (schowek 0..N-1): równoważny setStorageContents i w pełni wspierany
                // przez środowiska testowe. Przywraca DOKŁADNIE snapshot (nic częściowego nie zostaje).
                for (int i = 0; i < snapshot.length; i++) {
                    inv.setItem(i, snapshot[i]);
                }
            }
        };
    }

    /**
     * Produkcyjny wariant dla wątku głównego: opakowuje {@link PlayerInventory} i zwraca
     * PEŁNY tri-state. Samo utworzenie adaptera jest chronione - {@code null}/nieprawidłowy
     * gracz/przedmiot lub błąd adaptera dają {@link Result#STATE_UNCERTAIN}. Nigdy nie rzuca.
     *
     * WAŻNE: przy {@link Result#STATE_UNCERTAIN} część przedmiotu mogła już trafić do
     * ekwipunku - wołający NIE może wtedy tworzyć item-claim, zwracać pełnych pieniędzy
     * ani cofać claim-a (ryzyko duplikacji/nadkompensacji). Rozstrzygnięcie należy do wołającego.
     */
    public static Result tryAddFull(Player player, ItemStack stack) {
        if (player == null || stack == null) {
            return Result.STATE_UNCERTAIN;
        }
        InventoryAccess access;
        try {
            access = playerAccess(player);
        } catch (Throwable t) {
            return Result.STATE_UNCERTAIN;
        }
        return tryAddFull(access, stack);
    }

    private static ItemStack[] deepCopy(ItemStack[] contents) {
        ItemStack[] out = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            out[i] = contents[i] == null ? null : contents[i].clone();
        }
        return out;
    }
}
