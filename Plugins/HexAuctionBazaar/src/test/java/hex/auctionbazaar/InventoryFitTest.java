package hex.auctionbazaar;

import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.auction.service.AuctionService.ItemRefundStatus;
import hex.auctionbazaar.util.InventoryFit;
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

import java.lang.reflect.Method;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkty #2/#3: all-or-nothing, ODPORNE NA WYJĄTKI dokładanie do ekwipunku plus
 * mapowanie na śledzoną rekompensatę ({@link AuctionService#resolveItemReturn}).
 *
 * Dostęp do ekwipunku jest odseparowany przez {@link InventoryFit.InventoryAccess},
 * dzięki czemu wszystkie ścieżki (w tym revert i wyjątki snapshot/add/revert) są
 * testowane BEZ serwera i BEZ {@code @Disabled} - atrapą, którą w pełni kontrolujemy.
 * MockBukkit służy jedynie do budowy {@link ItemStack} z meta (ItemFactory).
 */
class InventoryFitTest {

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

    /** Kontrolowana atrapa schowka ekwipunku: pozwala wymusić przepełnienie i wyjątki. */
    private static final class FakeInventory implements InventoryFit.InventoryAccess {
        ItemStack[] storage;
        ItemStack overflowLeftover;                 // != null -> addItem zwraca resztę (przepełnienie)
        boolean throwOnSnapshot, throwOnAdd, throwOnRestore;
        boolean nullSnapshot, nullLeftover;         // wymuś null z snapshotStorage()/addItem()
        int throwOnAddNo = -1;                       // rzuć wyłącznie na N-tym wywołaniu addItem (batch)
        int overflowOnAddNo = -1;                    // przepełnienie wyłącznie na N-tym addItem (batch)
        int snapshotCalls, addCalls, restoreCalls;

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
        public Map<Integer, ItemStack> addItem(ItemStack stack) {
            addCalls++;
            if (throwOnAdd || throwOnAddNo == addCalls) {
                throw new RuntimeException("add boom");
            }
            if (nullLeftover) {
                return null;                         // nieznana reszta -> wołający musi uznać za niepewne
            }
            // Symuluj Bukkit: WPISZ do pierwszego wolnego slotu (mutacja ekwipunku) PRZED zwrotem reszty.
            for (int i = 0; i < storage.length; i++) {
                if (storage[i] == null) {
                    storage[i] = stack.clone();
                    break;
                }
            }
            boolean overflow = overflowLeftover != null
                    && (overflowOnAddNo == -1 || overflowOnAddNo == addCalls);
            if (overflow) {
                Map<Integer, ItemStack> leftover = new HashMap<>();
                leftover.put(0, overflowLeftover.clone());
                return leftover;                    // nie zmieściło się w całości
            }
            return new HashMap<>();                  // pusta reszta -> zmieściło się w całości
        }

        @Override
        public void restoreStorage(ItemStack[] snapshot) {
            restoreCalls++;
            if (throwOnRestore) {
                throw new RuntimeException("restore boom");
            }
            storage = deepCopy(snapshot);
        }

        static ItemStack[] deepCopy(ItemStack[] in) {
            ItemStack[] out = new ItemStack[in.length];
            for (int i = 0; i < in.length; i++) {
                out[i] = in[i] == null ? null : in[i].clone();
            }
            return out;
        }
    }

    private static ItemStack decorated() {
        NamespacedKey key = new NamespacedKey("hexauctionbazaar", "test");
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Legendarny Miecz"));
        meta.lore(List.of(Component.text("Ostrze mocy")));
        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        meta.setCustomModelData(42);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "abc123");
        item.setItemMeta(meta);
        return item;
    }

    private static void assertDecorationPreserved(ItemStack stored, ItemStack original) {
        assertNotNull(stored);
        // isSimilar porównuje PEŁNE meta (nazwa, lore, zaklęcia, CustomModelData, PDC).
        assertTrue(stored.isSimilar(original), "meta/NBT identyczne");
        ItemMeta sm = stored.getItemMeta();
        assertTrue(sm.hasDisplayName(), "nazwa zachowana");
        assertNotNull(sm.lore());
        assertEquals(1, sm.lore().size(), "lore zachowane");
        assertEquals(42, sm.getCustomModelData(), "CustomModelData zachowane");
        assertTrue(sm.hasEnchant(Enchantment.SHARPNESS));
        assertEquals(5, sm.getEnchantLevel(Enchantment.SHARPNESS), "poziom zaklęcia zachowany");
        assertEquals("abc123", sm.getPersistentDataContainer()
                .get(new NamespacedKey("hexauctionbazaar", "test"), PersistentDataType.STRING), "PDC zachowane");
    }

    // ----------------------------------------------------------------- tryAddFull (seam)

    @Test
    void fullFitIsAddedOnceNoRevert() {
        FakeInventory inv = new FakeInventory(9);
        ItemStack item = new ItemStack(Material.DIAMOND, 5);

        InventoryFit.Result r = InventoryFit.tryAddFull(inv, item);

        assertEquals(InventoryFit.Result.ADDED_FULLY, r);
        assertEquals(1, inv.addCalls, "addItem wołane raz");
        assertEquals(0, inv.restoreCalls, "przy pełnym dodaniu nie ma revertu");
        int found = 0;
        for (ItemStack s : inv.storage) {
            if (s != null && s.getType() == Material.DIAMOND) {
                found += s.getAmount();
            }
        }
        assertEquals(5, found, "przedmiot dodany dokładnie raz");
    }

    @Test
    void overflowRevertsSlotForSlotWithNoPartialLeftover() {
        FakeInventory inv = new FakeInventory(9);
        inv.storage[0] = new ItemStack(Material.STONE, 64);
        inv.storage[3] = new ItemStack(Material.DIRT, 32);
        inv.storage[7] = new ItemStack(Material.GOLD_INGOT, 10);
        ItemStack[] before = FakeInventory.deepCopy(inv.storage);
        inv.overflowLeftover = new ItemStack(Material.DIAMOND, 2);   // wymuś niepełne zmieszczenie

        InventoryFit.Result r = InventoryFit.tryAddFull(inv, new ItemStack(Material.DIAMOND, 5));

        assertEquals(InventoryFit.Result.NOT_FIT_REVERTED, r);
        assertEquals(1, inv.restoreCalls, "przepełnienie -> revert");
        for (int i = 0; i < before.length; i++) {
            if (before[i] == null) {
                assertNull(inv.storage[i], "slot " + i + " pozostał pusty (slot-for-slot)");
            } else {
                assertNotNull(inv.storage[i], "slot " + i + " przywrócony");
                assertTrue(before[i].isSimilar(inv.storage[i]), "slot " + i + " identyczny po revercie");
                assertEquals(before[i].getAmount(), inv.storage[i].getAmount(), "ilość w slocie " + i);
            }
        }
        for (ItemStack s : inv.storage) {
            assertFalse(s != null && s.getType() == Material.DIAMOND,
                    "all-or-nothing: żaden częściowy diament nie został");
        }
    }

    @Test
    void snapshotThrowingGivesStateUncertainWithoutAdd() {
        FakeInventory inv = new FakeInventory(9);
        inv.throwOnSnapshot = true;

        InventoryFit.Result r = InventoryFit.tryAddFull(inv, new ItemStack(Material.DIAMOND, 5));

        assertEquals(InventoryFit.Result.STATE_UNCERTAIN, r);
        assertEquals(0, inv.addCalls, "po błędzie snapshotu nie próbujemy dodawać");
    }

    @Test
    void addThrowingGivesStateUncertainWithoutRevert() {
        FakeInventory inv = new FakeInventory(9);
        inv.throwOnAdd = true;

        InventoryFit.Result r = InventoryFit.tryAddFull(inv, new ItemStack(Material.DIAMOND, 5));

        assertEquals(InventoryFit.Result.STATE_UNCERTAIN, r);
        assertEquals(0, inv.restoreCalls,
                "po wyjątku addItem NIE robimy revertu (ekwipunek mógł być już częściowo zmieniony)");
    }

    @Test
    void restoreThrowingGivesStateUncertain() {
        FakeInventory inv = new FakeInventory(9);
        inv.overflowLeftover = new ItemStack(Material.DIAMOND, 2);
        inv.throwOnRestore = true;

        InventoryFit.Result r = InventoryFit.tryAddFull(inv, new ItemStack(Material.DIAMOND, 5));

        assertEquals(InventoryFit.Result.STATE_UNCERTAIN, r);
    }

    @Test
    void nullInputsGiveStateUncertain() {
        FakeInventory inv = new FakeInventory(9);
        ItemStack empty = new ItemStack(Material.DIAMOND, 1);
        empty.setAmount(0);
        assertEquals(InventoryFit.Result.STATE_UNCERTAIN,
                InventoryFit.tryAddFull(inv, null), "null stack");
        assertEquals(InventoryFit.Result.STATE_UNCERTAIN,
                InventoryFit.tryAddFull((InventoryFit.InventoryAccess) null,
                        new ItemStack(Material.DIAMOND)), "null inventory");
        assertEquals(InventoryFit.Result.STATE_UNCERTAIN,
                InventoryFit.tryAddFull(inv, empty), "pusty stack");
    }

    @Test
    void directDeliveryPreservesMetaLoreEnchantCustomModelDataAndPdc() {
        FakeInventory inv = new FakeInventory(9);
        ItemStack original = decorated();

        InventoryFit.Result r = InventoryFit.tryAddFull(inv, original.clone());

        assertEquals(InventoryFit.Result.ADDED_FULLY, r);
        ItemStack stored = null;
        for (ItemStack s : inv.storage) {
            if (s != null && s.getType() == Material.DIAMOND_SWORD) {
                stored = s;
                break;
            }
        }
        assertDecorationPreserved(stored, original);
    }

    @Test
    void nullSnapshotGivesStateUncertainWithoutAdd() {
        FakeInventory inv = new FakeInventory(9);
        inv.nullSnapshot = true;
        InventoryFit.Result r = InventoryFit.tryAddFull(inv, new ItemStack(Material.DIAMOND, 5));
        assertEquals(InventoryFit.Result.STATE_UNCERTAIN, r);
        assertEquals(0, inv.addCalls, "bez wiarygodnego snapshotu nie dodajemy");
    }

    @Test
    void nullLeftoverGivesStateUncertainNotAddedFully() {
        FakeInventory inv = new FakeInventory(9);
        inv.nullLeftover = true;
        InventoryFit.Result r = InventoryFit.tryAddFull(inv, new ItemStack(Material.DIAMOND, 5));
        assertEquals(InventoryFit.Result.STATE_UNCERTAIN, r,
                "null reszta = nieznany stan, a NIE ADDED_FULLY");
        assertEquals(0, inv.restoreCalls, "przy niepewnym dodaniu nie robimy revertu");
    }

    // -------------------------------------------- produkcyjny overload tryAddFull(Player, ItemStack)

    @Test
    void prodOverloadNullPlayerOrStackIsUncertain() {
        assertEquals(InventoryFit.Result.STATE_UNCERTAIN,
                InventoryFit.tryAddFull((Player) null, new ItemStack(Material.DIAMOND)), "null gracz");
        PlayerMock p = server.addPlayer("Buyer");
        assertEquals(InventoryFit.Result.STATE_UNCERTAIN,
                InventoryFit.tryAddFull(p, null), "null przedmiot");
    }

    @Test
    void prodOverloadFullFitReturnsAddedFully() {
        PlayerMock p = server.addPlayer("Buyer");
        InventoryFit.Result r = InventoryFit.tryAddFull(p, new ItemStack(Material.DIAMOND, 5));
        assertEquals(InventoryFit.Result.ADDED_FULLY, r, "pusty ekwipunek mieści cały stack");
        assertTrue(p.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND), 5));
    }

    @Test
    void noBooleanCompatWrapperRemains() {
        for (Method m : InventoryFit.class.getDeclaredMethods()) {
            org.junit.jupiter.api.Assertions.assertNotEquals("tryAddFullOrRevert", m.getName(),
                    "boolean-owy wrapper musi być całkowicie usunięty");
        }
    }

    // ----------------------------------------------------------------- tryAddAllFull (batch, punkt #1)

    @Test
    void batchAllStacksFitAddedFullyNoRevert() {
        FakeInventory inv = new FakeInventory(9);
        List<ItemStack> stacks = List.of(new ItemStack(Material.DIAMOND, 64),
                new ItemStack(Material.DIAMOND, 64), new ItemStack(Material.GOLD_INGOT, 10));

        InventoryFit.Result r = InventoryFit.tryAddAllFull(inv, stacks);

        assertEquals(InventoryFit.Result.ADDED_FULLY, r);
        assertEquals(3, inv.addCalls, "każdy stack dodany");
        assertEquals(0, inv.restoreCalls, "pełne dodanie -> bez revertu");
    }

    @Test
    void batchOverflowOnSecondRevertsEverythingExactly() {
        // Pierwszy stack się mieści, DRUGI przepełnia -> all-or-nothing MUSI cofnąć również pierwszy.
        FakeInventory inv = new FakeInventory(9);
        inv.storage[0] = new ItemStack(Material.STONE, 64);
        inv.storage[5] = new ItemStack(Material.DIRT, 10);
        ItemStack[] before = FakeInventory.deepCopy(inv.storage);
        inv.overflowLeftover = new ItemStack(Material.DIAMOND, 2);
        inv.overflowOnAddNo = 2;   // przepełnienie dopiero na drugim stacku

        InventoryFit.Result r = InventoryFit.tryAddAllFull(inv,
                List.of(new ItemStack(Material.DIAMOND, 64), new ItemStack(Material.DIAMOND, 64)));

        assertEquals(InventoryFit.Result.NOT_FIT_REVERTED, r);
        assertEquals(1, inv.restoreCalls, "dokładnie jeden revert całego snapshotu");
        for (int i = 0; i < before.length; i++) {
            if (before[i] == null) {
                assertNull(inv.storage[i], "slot " + i + " pozostał pusty (nic częściowego)");
            } else {
                assertTrue(before[i].isSimilar(inv.storage[i]) && before[i].getAmount() == inv.storage[i].getAmount(),
                        "slot " + i + " przywrócony dokładnie");
            }
        }
        for (ItemStack s : inv.storage) {
            assertFalse(s != null && s.getType() == Material.DIAMOND,
                    "żaden (nawet pierwszy) diament nie został po revercie");
        }
    }

    @Test
    void batchSecondAddThrowsAfterFirstMutationIsUncertainNoRevert() {
        FakeInventory inv = new FakeInventory(9);
        inv.throwOnAddNo = 2;   // pierwszy add OK (mutacja), drugi rzuca

        InventoryFit.Result r = InventoryFit.tryAddAllFull(inv,
                List.of(new ItemStack(Material.DIAMOND, 64), new ItemStack(Material.DIAMOND, 64)));

        assertEquals(InventoryFit.Result.STATE_UNCERTAIN, r);
        assertEquals(2, inv.addCalls, "próbowaliśmy dodać drugi stack");
        assertEquals(0, inv.restoreCalls, "po wyjątku add NIE robimy revertu (stan już zmieniony)");
    }

    @Test
    void batchRestoreThrowsIsUncertain() {
        FakeInventory inv = new FakeInventory(9);
        inv.overflowLeftover = new ItemStack(Material.DIAMOND, 2);   // wymuś revert
        inv.throwOnRestore = true;

        InventoryFit.Result r = InventoryFit.tryAddAllFull(inv,
                List.of(new ItemStack(Material.DIAMOND, 64)));

        assertEquals(InventoryFit.Result.STATE_UNCERTAIN, r);
    }

    @Test
    void batchSnapshotNullOrThrowIsUncertainWithoutAdd() {
        FakeInventory nullSnap = new FakeInventory(9);
        nullSnap.nullSnapshot = true;
        assertEquals(InventoryFit.Result.STATE_UNCERTAIN,
                InventoryFit.tryAddAllFull(nullSnap, List.of(new ItemStack(Material.DIAMOND, 1))));
        assertEquals(0, nullSnap.addCalls, "bez wiarygodnego snapshotu nie dodajemy");

        FakeInventory throwSnap = new FakeInventory(9);
        throwSnap.throwOnSnapshot = true;
        assertEquals(InventoryFit.Result.STATE_UNCERTAIN,
                InventoryFit.tryAddAllFull(throwSnap, List.of(new ItemStack(Material.DIAMOND, 1))));
        assertEquals(0, throwSnap.addCalls);
    }

    @Test
    void batchEmptyListIsAddedFullyAndNullElementUncertain() {
        FakeInventory inv = new FakeInventory(9);
        assertEquals(InventoryFit.Result.ADDED_FULLY, InventoryFit.tryAddAllFull(inv, List.of()));
        assertEquals(0, inv.snapshotCalls, "pusta lista nie rusza ekwipunku");

        FakeInventory inv2 = new FakeInventory(9);
        java.util.List<ItemStack> withNull = new java.util.ArrayList<>();
        withNull.add(new ItemStack(Material.DIAMOND, 1));
        withNull.add(null);
        assertEquals(InventoryFit.Result.STATE_UNCERTAIN, InventoryFit.tryAddAllFull(inv2, withNull),
                "null w liście = niepewne, nic nie dotykamy");
        assertEquals(0, inv2.snapshotCalls);
    }

    @Test
    void batchNullInventoryOrListIsUncertain() {
        assertEquals(InventoryFit.Result.STATE_UNCERTAIN,
                InventoryFit.tryAddAllFull((InventoryFit.InventoryAccess) null,
                        List.of(new ItemStack(Material.DIAMOND, 1))));
        assertEquals(InventoryFit.Result.STATE_UNCERTAIN,
                InventoryFit.tryAddAllFull(new FakeInventory(9), (List<ItemStack>) null));
    }

    // ----------------------------------------------------- resolveItemReturn (mapowanie)

    private static Function<ItemStack, CompletableFuture<Boolean>> claim(boolean ok, AtomicInteger calls) {
        return it -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(ok);
        };
    }

    @Test
    void addedFullyResolvesToDeliveredWithoutClaim() {
        AtomicInteger calls = new AtomicInteger();
        ItemRefundStatus st = AuctionService.resolveItemReturn(
                true, InventoryFit.Result.ADDED_FULLY, new ItemStack(Material.DIAMOND), claim(true, calls)).join();
        assertEquals(ItemRefundStatus.DELIVERED, st);
        assertEquals(0, calls.get(), "przy dostawie do ekwipunku nie zapisujemy claim");
    }

    @Test
    void notFitRevertedClaimsFullItemOnceThenClaimed() {
        AtomicInteger calls = new AtomicInteger();
        ItemRefundStatus st = AuctionService.resolveItemReturn(
                true, InventoryFit.Result.NOT_FIT_REVERTED, new ItemStack(Material.DIAMOND), claim(true, calls)).join();
        assertEquals(ItemRefundStatus.CLAIMED, st);
        assertEquals(1, calls.get(), "pełny przedmiot jako dokładnie jeden claim");
    }

    @Test
    void notFitRevertedWithFailedInsertIsFailed() {
        AtomicInteger calls = new AtomicInteger();
        ItemRefundStatus st = AuctionService.resolveItemReturn(
                true, InventoryFit.Result.NOT_FIT_REVERTED, new ItemStack(Material.DIAMOND), claim(false, calls)).join();
        assertEquals(ItemRefundStatus.FAILED, st);
        assertEquals(1, calls.get());
    }

    @Test
    void stateUncertainResolvesToFailedWithNoClaimInsert() {
        AtomicInteger calls = new AtomicInteger();
        // KLUCZOWE: niepewny stan ekwipunku NIE może zapisać claim (część mogła trafić do ekwipunku -> dup).
        ItemRefundStatus st = AuctionService.resolveItemReturn(
                true, InventoryFit.Result.STATE_UNCERTAIN, new ItemStack(Material.DIAMOND), claim(true, calls)).join();
        assertEquals(ItemRefundStatus.FAILED, st);
        assertEquals(0, calls.get(), "STATE_UNCERTAIN nigdy nie wstawia claim");
    }

    @Test
    void offlinePlayerPathClaimsFullItemOnce() {
        AtomicInteger calls = new AtomicInteger();
        // Gracz offline: nie próbowano ekwipunku -> claim pełnego przedmiotu.
        ItemRefundStatus st = AuctionService.resolveItemReturn(
                false, InventoryFit.Result.NOT_FIT_REVERTED, new ItemStack(Material.DIAMOND), claim(true, calls)).join();
        assertEquals(ItemRefundStatus.CLAIMED, st);
        assertEquals(1, calls.get());
    }

    @Test
    void futureAlwaysCompletesEvenWhenClaimInsertThrows() {
        Function<ItemStack, CompletableFuture<Boolean>> boom = it -> {
            throw new RuntimeException("insert boom");
        };
        CompletableFuture<ItemRefundStatus> f = AuctionService.resolveItemReturn(
                true, InventoryFit.Result.NOT_FIT_REVERTED, new ItemStack(Material.DIAMOND), boom);
        assertTrue(f.isDone(), "future domknięty mimo wyjątku claim");
        assertEquals(ItemRefundStatus.FAILED, f.join());
    }

    @Test
    void claimPathReceivesIntactItemForSerialization() {
        // Dowodzi, że do serializacji claim trafia NIEZMIENIONY oryginał (meta/NBT/PDC),
        // czyli to ten sam przedmiot, który produkcyjnie serializuje ItemSerializer.
        ItemStack original = decorated();
        AtomicReference<ItemStack> captured = new AtomicReference<>();
        Function<ItemStack, CompletableFuture<Boolean>> capturing = it -> {
            captured.set(it);
            return CompletableFuture.completedFuture(true);
        };
        ItemRefundStatus st = AuctionService.resolveItemReturn(
                true, InventoryFit.Result.NOT_FIT_REVERTED, original, capturing).join();
        assertEquals(ItemRefundStatus.CLAIMED, st);
        assertSame(original, captured.get(), "do claim trafia dokładnie oryginalny przedmiot");
        assertDecorationPreserved(captured.get(), original);
    }
}
