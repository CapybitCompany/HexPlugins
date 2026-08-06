package hex.auctionbazaar.util;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Bezpieczne, śledzone ZDEJMOWANIE przedmiotów z ekwipunku ze snapshotem i typizowanym wynikiem
 * (punkt #1). Odpowiednik {@link InventoryFit} dla kierunku „w drugą stronę": robimy JEDEN głęboki
 * snapshot schowka, liczymy pasujące sztuki i zdejmujemy je per-slot. Kluczowe: gdy po kilku udanych
 * slotach {@code setSlot()} rzuci, próbujemy przywrócić DOKŁADNIE snapshot (all-or-nothing) - dzięki
 * czemu nie zostaje częściowo opróżniony ekwipunek, którego stan trudno rozstrzygnąć.
 *
 * <p>Wynik jest rozłączny i decyduje o zachowaniu wołającego:
 * <ul>
 *   <li>{@link Status#REMOVED} - wszystko zdjęte; {@link Result#removed()} to DOKŁADNE zdjęte stacki
 *       (klony z pełnym NBT/PDC/nazwą/lore/enchantami/CustomModelData);</li>
 *   <li>{@link Status#NOT_ENOUGH} - za mało pasujących sztuk; ekwipunek NIETKNIĘTY;</li>
 *   <li>{@link Status#REVERTED} - błąd zdejmowania, ale ekwipunek bezpiecznie przywrócony do snapshotu
 *       (nic nie zdjęto) albo błąd wystąpił PRZED jakąkolwiek mutacją - stan BEZPIECZNY;</li>
 *   <li>{@link Status#STATE_UNCERTAIN} - zdejmowanie częściowo zmutowało ekwipunek i przywrócenie się
 *       nie powiodło - stanu NIE da się bezpiecznie ustalić; wołający NIE zwraca automatycznie
 *       wszystkich oryginalnych przedmiotów (ryzyko duplikacji) - to stan krytyczny.</li>
 * </ul>
 *
 * <p>Dostęp do ekwipunku jest odseparowany przez {@link InventoryAccess}, więc logikę można testować
 * bez serwera (test wstrzykuje błąd na wybranym slocie). Metoda NIGDY nie rzuca - każdy wyjątek kończy
 * się typizowanym {@link Status}. Cały dostęp do ekwipunku musi odbywać się na wątku głównym (odpowiada
 * za to caller).
 */
public final class InventoryExtract {

    /** Rozłączne stany zdjęcia. Semantyka bezpieczeństwa (patrz klasa). */
    public enum Status {
        /** Wszystko zdjęte; zdjęte stacki dostępne w {@link Result#removed()}. */
        REMOVED,
        /** Za mało pasujących sztuk; ekwipunek nietknięty. */
        NOT_ENOUGH,
        /** Błąd, ale ekwipunek bezpiecznie przywrócony/nietknięty - nic nie zdjęto (stan bezpieczny). */
        REVERTED,
        /** Częściowa mutacja + nieudane przywrócenie - stan niepewny, BEZ automatycznego zwrotu (krytyczny). */
        STATE_UNCERTAIN
    }

    /** Wynik zdjęcia: status + (dla {@link Status#REMOVED}) dokładnie zdjęte stacki. */
    public record Result(Status status, List<ItemStack> removed) {
        public Result {
            removed = removed == null ? List.of() : List.copyOf(removed);
        }

        static Result removed(List<ItemStack> stacks) {
            return new Result(Status.REMOVED, stacks);
        }

        static Result of(Status status) {
            return new Result(status, List.of());
        }
    }

    /** Odseparowany dostęp do schowka ekwipunku (produkcja: {@link #playerAccess}; testy: atrapa). */
    public interface InventoryAccess {
        /** Głęboka kopia wszystkich slotów schowka (baseline do zliczania i przywrócenia). */
        ItemStack[] snapshotStorage();

        /** Ustawia zawartość slotu schowka ({@code null} = pusty). */
        void setSlot(int slot, ItemStack stack);

        /** Przywraca dokładnie podany snapshot schowka (all-or-nothing revert). */
        void restoreStorage(ItemStack[] snapshot);
    }

    private InventoryExtract() {
    }

    /**
     * Zdejmuje {@code needed} sztuk przedmiotów akceptowanych przez {@code selectable}. Wszystkie dostępy
     * do ekwipunku muszą odbywać się na wątku głównym (odpowiada za to caller). Nigdy nie rzuca.
     */
    public static Result extract(InventoryAccess inv, int needed, Predicate<ItemStack> selectable) {
        if (inv == null || selectable == null || needed <= 0) {
            // Brak jakiejkolwiek mutacji -> bezpiecznie (nic nie zdjęto).
            return Result.of(Status.REVERTED);
        }
        ItemStack[] snapshot;
        try {
            snapshot = inv.snapshotStorage();
        } catch (Throwable t) {
            // Snapshot padł PRZED jakąkolwiek mutacją - ekwipunek nietknięty.
            return Result.of(Status.REVERTED);
        }
        if (snapshot == null) {
            return Result.of(Status.REVERTED);
        }

        List<ItemStack> removed = new ArrayList<>();
        boolean mutated = false;
        try {
            long have = 0;
            for (ItemStack it : snapshot) {
                if (it == null || !selectable.test(it)) continue;
                have += it.getAmount();
                if (have >= needed) break;
            }
            if (have < needed) {
                // Za mało pasujących sztuk - nic nie zdjęto.
                return Result.of(Status.NOT_ENOUGH);
            }
            int remaining = needed;
            for (int slot = 0; slot < snapshot.length && remaining > 0; slot++) {
                ItemStack it = snapshot[slot];
                if (it == null || !selectable.test(it)) continue;
                int take = Math.min(it.getAmount(), remaining);
                ItemStack taken = it.clone();          // zachowuje NBT/PDC/meta oryginału
                taken.setAmount(take);
                int leftover = it.getAmount() - take;
                ItemStack newValue;
                if (leftover <= 0) {
                    newValue = null;
                } else {
                    ItemStack copy = it.clone();
                    copy.setAmount(leftover);
                    newValue = copy;
                }
                mutated = true;                        // od tego momentu ekwipunek mógł się zmienić
                inv.setSlot(slot, newValue);
                removed.add(taken);
                remaining -= take;
            }
            return Result.removed(removed);
        } catch (Throwable t) {
            if (!mutated) {
                // Błąd przed jakąkolwiek mutacją (np. predykat) - ekwipunek nietknięty.
                return Result.of(Status.REVERTED);
            }
            try {
                inv.restoreStorage(snapshot);
                // Przywrócono DOKŁADNIE snapshot - nic nie zostało zdjęte (bezpiecznie).
                return Result.of(Status.REVERTED);
            } catch (Throwable t2) {
                // Częściowa mutacja + nieudane przywrócenie - stanu nie da się ustalić (krytyczne).
                return Result.of(Status.STATE_UNCERTAIN);
            }
        }
    }

    /**
     * Produkcyjny wariant dla wątku głównego (Player). Chroniony - {@code null}/nieprawidłowy gracz lub
     * błąd adaptera daje {@link Status#REVERTED} (nic nie zdjęto). Nigdy nie rzuca.
     */
    public static Result extract(Player player, int needed, Predicate<ItemStack> selectable) {
        if (player == null) {
            return Result.of(Status.REVERTED);
        }
        InventoryAccess access;
        try {
            access = playerAccess(player);
        } catch (Throwable t) {
            return Result.of(Status.REVERTED);
        }
        return extract(access, needed, selectable);
    }

    /** Produkcyjny adapter opakowujący {@link PlayerInventory} (schowek: sloty 0..N-1). */
    public static InventoryAccess playerAccess(Player player) {
        final PlayerInventory inv = player.getInventory();
        return new InventoryAccess() {
            @Override
            public ItemStack[] snapshotStorage() {
                return deepCopy(inv.getStorageContents());
            }

            @Override
            public void setSlot(int slot, ItemStack stack) {
                inv.setItem(slot, stack);
            }

            @Override
            public void restoreStorage(ItemStack[] snapshot) {
                // Zapis per-slot (schowek 0..N-1): równoważny setStorageContents i w pełni wspierany przez
                // środowiska testowe. Przywraca DOKŁADNIE snapshot (nic częściowego nie zostaje).
                for (int i = 0; i < snapshot.length; i++) {
                    inv.setItem(i, snapshot[i]);
                }
            }
        };
    }

    private static ItemStack[] deepCopy(ItemStack[] contents) {
        ItemStack[] out = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            out[i] = contents[i] == null ? null : contents[i].clone();
        }
        return out;
    }
}
