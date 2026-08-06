package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.service.BazaarOrderService;
import hex.auctionbazaar.bazaar.service.BazaarOrderService.PlaceResult;
import hex.auctionbazaar.util.InventoryExtract;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #1: śledzone ZDEJMOWANIE przedmiotów ze snapshotem i typizowanym wynikiem. Dostęp do ekwipunku
 * jest odseparowany przez {@link InventoryExtract.InventoryAccess}, więc WSZYSTKIE ścieżki (snapshot,
 * pierwszy i późniejszy slot, przywrócenie) są testowane BEZ serwera - atrapą, którą w pełni kontrolujemy.
 * Kluczowe: częściowa awaria zdejmowania NIGDY nie zostawia częściowo opróżnionego ekwipunku ani nie
 * prowadzi do automatycznego zwrotu wszystkiego przy niepewnym stanie.
 */
class InventoryExtractTest {

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

    /** Kontrolowana atrapa schowka: pozwala wymusić wyjątki snapshotu, konkretnego slotu i przywrócenia. */
    private static final class FakeInventory implements InventoryExtract.InventoryAccess {
        ItemStack[] storage;
        boolean throwOnSnapshot, nullSnapshot, throwOnRestore;
        int throwOnSetSlotNo = -1;   // rzuć wyłącznie na N-tym wywołaniu setSlot (przed mutacją)
        int snapshotCalls, setSlotCalls, restoreCalls;

        FakeInventory(int size) {
            storage = new ItemStack[size];
        }

        @Override
        public ItemStack[] snapshotStorage() {
            snapshotCalls++;
            if (throwOnSnapshot) {
                throw new RuntimeException("snapshot boom");
            }
            return nullSnapshot ? null : deepCopy(storage);
        }

        @Override
        public void setSlot(int slot, ItemStack stack) {
            setSlotCalls++;
            if (throwOnSetSlotNo == setSlotCalls) {
                throw new RuntimeException("setSlot boom @" + setSlotCalls);   // przed mutacją
            }
            storage[slot] = stack;
        }

        @Override
        public void restoreStorage(ItemStack[] snapshot) {
            restoreCalls++;
            if (throwOnRestore) {
                throw new RuntimeException("restore boom");
            }
            storage = deepCopy(snapshot);
        }

        int countDiamonds() {
            int total = 0;
            for (ItemStack s : storage) {
                if (s != null && s.getType() == Material.DIAMOND) {
                    total += s.getAmount();
                }
            }
            return total;
        }

        static ItemStack[] deepCopy(ItemStack[] in) {
            ItemStack[] out = new ItemStack[in.length];
            for (int i = 0; i < in.length; i++) {
                out[i] = in[i] == null ? null : in[i].clone();
            }
            return out;
        }
    }

    private static java.util.function.Predicate<ItemStack> diamond() {
        return it -> it.getType() == Material.DIAMOND;
    }

    // ----------------------------------------------------------------- podstawowe ścieżki

    @Test
    void notEnoughLeavesInventoryUntouched() {
        FakeInventory inv = new FakeInventory(9);
        inv.storage[0] = new ItemStack(Material.DIAMOND, 3);

        InventoryExtract.Result r = InventoryExtract.extract(inv, 5, diamond());

        assertEquals(InventoryExtract.Status.NOT_ENOUGH, r.status());
        assertTrue(r.removed().isEmpty());
        assertEquals(0, inv.setSlotCalls, "za mało -> nic nie zdejmujemy");
        assertEquals(3, inv.countDiamonds(), "ekwipunek nietknięty");
    }

    @Test
    void enoughRemovesExactStacksAcrossSlots() {
        FakeInventory inv = new FakeInventory(9);
        inv.storage[0] = new ItemStack(Material.DIAMOND, 3);
        inv.storage[2] = new ItemStack(Material.STONE, 10);   // pomijany (inny materiał)
        inv.storage[4] = new ItemStack(Material.DIAMOND, 4);

        InventoryExtract.Result r = InventoryExtract.extract(inv, 5, diamond());

        assertEquals(InventoryExtract.Status.REMOVED, r.status());
        int removedTotal = r.removed().stream().mapToInt(ItemStack::getAmount).sum();
        assertEquals(5, removedTotal, "zdjęto dokładnie tyle, ile potrzeba");
        assertEquals(2, inv.countDiamonds(), "w ekwipunku zostały 2 diamenty (7 - 5)");
        assertNotNull(inv.storage[2], "kamień (inny materiał) nietknięty");
        assertEquals(Material.STONE, inv.storage[2].getType());
        assertEquals(10, inv.storage[2].getAmount(), "kamień (inny materiał) nietknięty");
    }

    @Test
    void removedStacksPreserveMetaNbtEnchantsLoreCustomModelData() {
        FakeInventory inv = new FakeInventory(9);
        ItemStack decorated = decoratedDiamond();
        inv.storage[0] = decorated.clone();

        InventoryExtract.Result r = InventoryExtract.extract(inv, 4,
                it -> it.getType() == Material.DIAMOND);

        assertEquals(InventoryExtract.Status.REMOVED, r.status());
        assertEquals(1, r.removed().size());
        ItemStack taken = r.removed().get(0);
        assertEquals(4, taken.getAmount());
        assertTrue(taken.isSimilar(decorated), "meta/NBT/lore/enchanty/CMD/PDC zachowane w zdjętym stacku");
    }

    // ----------------------------------------------------------------- snapshot (przed mutacją)

    @Test
    void snapshotThrowingIsRevertedWithoutMutation() {
        FakeInventory inv = new FakeInventory(9);
        inv.throwOnSnapshot = true;

        InventoryExtract.Result r = InventoryExtract.extract(inv, 5, diamond());

        // Awaria PRZED jakąkolwiek mutacją -> bezpiecznie (nic nie zdjęto).
        assertEquals(InventoryExtract.Status.REVERTED, r.status());
        assertEquals(0, inv.setSlotCalls);
        assertEquals(0, inv.restoreCalls);
    }

    @Test
    void nullSnapshotIsRevertedWithoutMutation() {
        FakeInventory inv = new FakeInventory(9);
        inv.nullSnapshot = true;

        InventoryExtract.Result r = InventoryExtract.extract(inv, 5, diamond());

        assertEquals(InventoryExtract.Status.REVERTED, r.status());
        assertEquals(0, inv.setSlotCalls);
    }

    // ----------------------------------------------------------------- wyjątek na slocie

    @Test
    void exceptionOnFirstSlotRevertsSafely() {
        FakeInventory inv = new FakeInventory(9);
        inv.storage[0] = new ItemStack(Material.DIAMOND, 5);
        inv.throwOnSetSlotNo = 1;   // pierwszy setSlot rzuca (przed mutacją)

        InventoryExtract.Result r = InventoryExtract.extract(inv, 5, diamond());

        assertEquals(InventoryExtract.Status.REVERTED, r.status());
        assertEquals(1, inv.restoreCalls, "próba przywrócenia snapshotu");
        assertEquals(5, inv.countDiamonds(), "nic nie zdjęto - ekwipunek jak przed próbą");
        assertTrue(r.removed().isEmpty());
    }

    @Test
    void exceptionOnLaterSlotRevertsEverythingNoPartialRemoval() {
        // Slot 0 zdjęty pomyślnie, potem setSlot na slocie 2 rzuca -> MUSI cofnąć również slot 0.
        FakeInventory inv = new FakeInventory(9);
        inv.storage[0] = new ItemStack(Material.DIAMOND, 3);
        inv.storage[2] = new ItemStack(Material.DIAMOND, 3);
        inv.throwOnSetSlotNo = 2;   // drugi setSlot rzuca

        InventoryExtract.Result r = InventoryExtract.extract(inv, 5, diamond());

        assertEquals(InventoryExtract.Status.REVERTED, r.status(),
                "częściowa entnahme + wyjątek -> zwrot do snapshotu (bezpiecznie), a nie auto-claim");
        assertEquals(1, inv.restoreCalls);
        assertEquals(6, inv.countDiamonds(), "oba sloty przywrócone dokładnie (3+3)");
    }

    @Test
    void exceptionOnLaterSlotWithFailedRestoreIsStateUncertain() {
        FakeInventory inv = new FakeInventory(9);
        inv.storage[0] = new ItemStack(Material.DIAMOND, 3);
        inv.storage[2] = new ItemStack(Material.DIAMOND, 3);
        inv.throwOnSetSlotNo = 2;
        inv.throwOnRestore = true;   // przywrócenie też pada -> stan niepewny

        InventoryExtract.Result r = InventoryExtract.extract(inv, 5, diamond());

        assertEquals(InventoryExtract.Status.STATE_UNCERTAIN, r.status(),
                "częściowa mutacja + nieudany revert -> STATE_UNCERTAIN (bez automatycznego zwrotu)");
        assertTrue(r.removed().isEmpty(), "przy niepewnym stanie nie oddajemy listy zdjętych do auto-claim");
    }

    @Test
    void predicateThrowingBeforeMutationIsReverted() {
        FakeInventory inv = new FakeInventory(9);
        inv.storage[0] = new ItemStack(Material.DIAMOND, 5);
        java.util.function.Predicate<ItemStack> boom = it -> {
            throw new RuntimeException("predykat boom");
        };

        InventoryExtract.Result r = InventoryExtract.extract(inv, 5, boom);

        assertEquals(InventoryExtract.Status.REVERTED, r.status());
        assertEquals(0, inv.setSlotCalls, "błąd predykatu przed mutacją -> nic nie zdjęto");
    }

    @Test
    void invalidArgumentsAreRevertedNeverUncertain() {
        // Null/niepoprawne argumenty NIE mutują ekwipunku -> bezpieczny REVERTED (nie krytyczny UNCERTAIN).
        assertEquals(InventoryExtract.Status.REVERTED,
                InventoryExtract.extract((InventoryExtract.InventoryAccess) null, 5, diamond()).status());
        assertEquals(InventoryExtract.Status.REVERTED,
                InventoryExtract.extract(new FakeInventory(9), 0, diamond()).status(), "needed<=0");
        assertEquals(InventoryExtract.Status.REVERTED,
                InventoryExtract.extract(new FakeInventory(9), 5, null).status(), "null predykat");
    }

    // ----------------------------------------------------------------- produkcyjny overload (Player)

    @Test
    void playerOverloadRemovesFromRealInventory() {
        PlayerMock p = server.addPlayer("Seller");
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 8));

        InventoryExtract.Result r = InventoryExtract.extract(p, 5,
                it -> it.getType() == Material.DIAMOND);

        assertEquals(InventoryExtract.Status.REMOVED, r.status());
        int left = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s != null && s.getType() == Material.DIAMOND) left += s.getAmount();
        }
        assertEquals(3, left, "w ekwipunku zostały 3 diamenty (8 - 5)");
    }

    @Test
    void playerOverloadNotEnoughLeavesInventory() {
        PlayerMock p = server.addPlayer("Seller");
        p.getInventory().addItem(new ItemStack(Material.DIAMOND, 2));

        InventoryExtract.Result r = InventoryExtract.extract(p, 5,
                it -> it.getType() == Material.DIAMOND);

        assertEquals(InventoryExtract.Status.NOT_ENOUGH, r.status());
        assertTrue(p.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND), 2), "diamenty nietknięte");
    }

    @Test
    void nullPlayerIsReverted() {
        assertEquals(InventoryExtract.Status.REVERTED,
                InventoryExtract.extract((Player) null, 5, diamond()).status());
    }

    // ----------------------------------------------------------------- mapowanie na PlaceResult (punkt #1)

    @Test
    void classifyExtractFailureMapsSafeVsCritical() {
        assertEquals(PlaceResult.NOT_ENOUGH_ITEMS,
                BazaarOrderService.classifyExtractFailure(InventoryExtract.Status.NOT_ENOUGH));
        // Bezpiecznie (nic nie zdjęto) -> zwykły błąd, retry.
        assertEquals(PlaceResult.DB_FAILED,
                BazaarOrderService.classifyExtractFailure(InventoryExtract.Status.REVERTED));
        // Niepewny stan -> krytyczny (bez auto-zwrotu, SEVERE po stronie wołającego).
        assertEquals(PlaceResult.COMPENSATION_FAILED,
                BazaarOrderService.classifyExtractFailure(InventoryExtract.Status.STATE_UNCERTAIN));
        assertThrows(IllegalStateException.class,
                () -> BazaarOrderService.classifyExtractFailure(InventoryExtract.Status.REMOVED),
                "REMOVED nie jest błędem zdjęcia");
    }

    // ----------------------------------------------------------------- helpers

    private static ItemStack decoratedDiamond() {
        NamespacedKey key = new NamespacedKey("hexauctionbazaar", "extract-test");
        ItemStack item = new ItemStack(Material.DIAMOND, 4);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Magiczny Diament"));
        meta.lore(List.of(Component.text("Lśni")));
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.setCustomModelData(7);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "xyz");
        item.setItemMeta(meta);
        return item;
    }
}
