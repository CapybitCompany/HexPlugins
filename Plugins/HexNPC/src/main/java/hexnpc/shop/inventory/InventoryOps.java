package hexnpc.shop.inventory;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Pomocnicze operacje all-or-nothing na ekwipunku gracza. Każda
 * funkcja najpierw symuluje operację i mutuje ekwipunek dopiero gdy
 * całość się powiedzie — chroni to transakcje shopu przed stanami
 * częściowymi (overflow ekwipunku, race condition).
 *
 * Mutacje ekwipunku Bukkita są dopuszczone tylko z głównego wątku —
 * wywołuj te metody w tickowym kontekście.
 */
public final class InventoryOps {

    private InventoryOps() {
    }

    /** Zwraca true, jeśli w ekwipunku jest miejsce na cały stos. */
    public static boolean canFitFully(PlayerInventory inv, ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) {
            return true;
        }
        return remainingCapacity(inv, stack) >= stack.getAmount();
    }

    /**
     * Dodaje stos tylko jeśli zmieści się w całości. Zwraca true przy
     * sukcesie. Przy dowolnym częściowym dodaniu (race z inną mutacją
     * ekwipunku) odtwarzamy snapshot sprzed wywołania, dzięki czemu
     * ekwipunek nigdy nie zostaje w stanie częściowym.
     */
    public static boolean giveAllOrNothing(Player player, ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) {
            return true;
        }
        PlayerInventory inv = player.getInventory();
        if (!canFitFully(inv, stack)) {
            return false;
        }
        ItemStack[] snapshot = cloneContents(inv.getStorageContents());
        Map<Integer, ItemStack> leftover = inv.addItem(stack.clone());
        if (leftover.isEmpty()) {
            return true;
        }
        // canFitFully zgodziło się, ale addItem zwróciło leftovers —
        // zakładamy równoległą mutację ekwipunku i przywracamy snapshot.
        restoreContents(inv, snapshot);
        return false;
    }

    /**
     * Wariant dla wielu stosów. Albo wszystkie się dodają, albo żaden.
     * Przy częściowym dodaniu wracamy do snapshotu sprzed wywołania.
     */
    public static boolean giveAllOrNothing(Player player, List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return true;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack[] snapshot = cloneContents(inv.getStorageContents());
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getAmount() <= 0) {
                continue;
            }
            Map<Integer, ItemStack> leftover = inv.addItem(stack.clone());
            if (!leftover.isEmpty()) {
                restoreContents(inv, snapshot);
                return false;
            }
        }
        return true;
    }

    /**
     * Liczy itemy w głównych slotach + hotbarze gracza, które spełniają
     * predykat. Pomija sloty zbroi i off-hand.
     */
    public static int countMatching(PlayerInventory inv, Predicate<ItemStack> predicate) {
        int total = 0;
        ItemStack[] contents = inv.getStorageContents();
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            if (predicate.test(stack)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /**
     * Usuwa dokładnie {@code amount} pasujących itemów i zwraca
     * faktycznie usunięte stosy (klony — zachowujemy metę). Gdy brakuje
     * zasobów, ekwipunek pozostaje nietknięty, a wynik to
     * {@link Optional#empty()}.
     *
     * Zwracanie realnych stosów pozwala callerom oddać lub zrzucić
     * dokładnie te itemy, które gracz miał — zamiast syntetycznego
     * material+amount, który po cichu zgubiłby nazwę/lore/enchant/PDC.
     */
    public static Optional<List<ItemStack>> removeAllOrNothing(PlayerInventory inv,
                                                               Predicate<ItemStack> predicate,
                                                               int amount) {
        if (amount <= 0) {
            return Optional.of(List.of());
        }
        if (countMatching(inv, predicate) < amount) {
            return Optional.empty();
        }
        List<ItemStack> removed = new ArrayList<>();
        int remaining = amount;
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            if (!predicate.test(stack)) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            int left = stack.getAmount() - take;
            ItemStack takenCopy = stack.clone();
            takenCopy.setAmount(take);
            removed.add(takenCopy);
            if (left <= 0) {
                inv.setItem(i, null);
            } else {
                ItemStack reduced = stack.clone();
                reduced.setAmount(left);
                inv.setItem(i, reduced);
            }
            remaining -= take;
        }
        if (remaining != 0) {
            // Nie powinno się zdarzyć po wcześniejszym pre-checku,
            // ale defensywnie: cofamy zmiany i zgłaszamy porażkę,
            // zamiast zostawiać ekwipunek w stanie pół-usuniętym.
            for (ItemStack r : removed) {
                inv.addItem(r);
            }
            return Optional.empty();
        }
        return Optional.of(List.copyOf(removed));
    }

    /**
     * Dzieli podaną ilość sztuk na listę stosów o rozmiarze do
     * {@code maxStackSize} materiału, zachowując metę szablonu. Dla itemów
     * niestakowalnych (maxStackSize == 1) powstaje po jednym stosie na sztukę.
     */
    public static List<ItemStack> split(ItemStack unit, long quantity) {
        List<ItemStack> stacks = new ArrayList<>();
        if (unit == null || quantity <= 0) {
            return stacks;
        }
        int maxStack = Math.max(1, unit.getMaxStackSize());
        long remaining = quantity;
        while (remaining > 0) {
            int take = (int) Math.min(maxStack, remaining);
            ItemStack copy = unit.clone();
            copy.setAmount(take);
            stacks.add(copy);
            remaining -= take;
        }
        return stacks;
    }

    /** True, jeśli w ekwipunku zmieści się {@code quantity} sztuk itemu. */
    public static boolean canFitQuantity(PlayerInventory inv, ItemStack unit, long quantity) {
        if (unit == null || quantity <= 0) {
            return true;
        }
        return remainingCapacity(inv, unit) >= quantity;
    }

    /**
     * Wręcza {@code quantity} sztuk itemu (rozbite na stosy) w trybie
     * all-or-nothing. Zwraca true przy pełnym sukcesie; przy jakimkolwiek
     * przecieku ekwipunek jest przywracany do stanu sprzed wywołania.
     */
    public static boolean giveQuantityAllOrNothing(Player player, ItemStack unit, long quantity) {
        if (unit == null || quantity <= 0) {
            return true;
        }
        if (!canFitQuantity(player.getInventory(), unit, quantity)) {
            return false;
        }
        return giveAllOrNothing(player, split(unit, quantity));
    }

    private static int remainingCapacity(PlayerInventory inv, ItemStack reference) {
        int capacity = 0;
        int maxStack = reference.getMaxStackSize();
        ItemStack[] contents = inv.getStorageContents();
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType() == Material.AIR) {
                capacity += maxStack;
                continue;
            }
            if (stack.isSimilar(reference)) {
                capacity += Math.max(0, maxStack - stack.getAmount());
            }
        }
        return capacity;
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] snapshot = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            snapshot[i] = contents[i] == null ? null : contents[i].clone();
        }
        return snapshot;
    }

    private static void restoreContents(PlayerInventory inv, ItemStack[] snapshot) {
        // Przywracamy slot po slocie. Unikamy setStorageContents, by
        // MockBukkit i niektóre forki (które różnie traktują długość
        // tablicy) były spójne w obie strony.
        for (int i = 0; i < snapshot.length; i++) {
            inv.setItem(i, snapshot[i] == null ? null : snapshot[i].clone());
        }
    }

    /**
     * Pomocnicze dla testów / logiki sprzedaży. Snapshot zawartości
     * ekwipunku jako mapa {Material -> łączna liczba} do asercji.
     */
    public static Map<Material, Integer> snapshot(PlayerInventory inv) {
        Map<Material, Integer> totals = new HashMap<>();
        for (ItemStack stack : inv.getStorageContents()) {
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            totals.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }
        return totals;
    }

    /**
     * Zwraca głęboki klon zawartości głównych slotów ekwipunku.
     * Używane w testach do asercji, że rollback zostawia ekwipunek
     * dokładnie w stanie sprzed operacji.
     */
    public static ItemStack[] cloneStorage(PlayerInventory inv) {
        return cloneContents(inv.getStorageContents());
    }
}
