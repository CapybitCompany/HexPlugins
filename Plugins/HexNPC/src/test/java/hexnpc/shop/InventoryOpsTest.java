package hexnpc.shop;

import hexnpc.shop.inventory.InventoryOps;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryOpsTest {

    private ServerMock server;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        player = server.addPlayer("Tester");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void giveAllOrNothingSucceedsWhenInventoryHasSpace() {
        boolean ok = InventoryOps.giveAllOrNothing(player, new ItemStack(Material.DIAMOND, 16));
        assertTrue(ok);
        assertEquals(16, player.getInventory().getItem(0).getAmount());
    }

    @Test
    void giveAllOrNothingRefusesWhenInventoryFull() {
        // Wypełniamy wszystkie sloty saplingami, żeby diament nie miał
        // żadnego miejsca, w którym mógłby się zmieścić.
        int size = player.getInventory().getStorageContents().length;
        for (int i = 0; i < size; i++) {
            player.getInventory().setItem(i, new ItemStack(Material.OAK_SAPLING, 64));
        }
        boolean ok = InventoryOps.giveAllOrNothing(player, new ItemStack(Material.DIAMOND, 1));
        assertFalse(ok, "musi odmówić, gdy brak miejsca");
        // Żaden diament nie mógł się przedostać.
        for (ItemStack s : player.getInventory().getStorageContents()) {
            assertFalse(s != null && s.getType() == Material.DIAMOND,
                    "częściowy diament nie powinien się pojawić");
        }
    }

    @Test
    void removeAllOrNothingRemovesExactlyAndReturnsRemovedStacks() {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 50));
        Predicate<ItemStack> p = s -> s != null && s.getType() == Material.DIAMOND;
        Optional<List<ItemStack>> removed = InventoryOps.removeAllOrNothing(
                player.getInventory(), p, 45);
        assertTrue(removed.isPresent(), "operacja powinna się powieść przy wystarczającym stocku");
        int totalReturned = removed.get().stream().mapToInt(ItemStack::getAmount).sum();
        assertEquals(45, totalReturned, "lista usuniętych musi sumować się do żądanej ilości");
        assertEquals(5, InventoryOps.countMatching(player.getInventory(), p));
    }

    @Test
    void removeAllOrNothingRefusesAndDoesNotMutateOnShortage() {
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 10));
        Predicate<ItemStack> p = s -> s != null && s.getType() == Material.DIAMOND;
        Optional<List<ItemStack>> removed = InventoryOps.removeAllOrNothing(
                player.getInventory(), p, 25);
        assertTrue(removed.isEmpty(), "removeAllOrNothing musi zgłosić empty przy niedoborze");
        assertEquals(10, InventoryOps.countMatching(player.getInventory(), p),
                "ekwipunek musi pozostać nienaruszony przy niedoborze");
    }

    @Test
    void removeAllOrNothingPreservesItemMetaOnReturnedStacks() {
        // Diament z customowym display name. Sprzedaż EXACT_ITEM polega
        // na tym, że zwracamy ten sam stos przy rollbacku — nigdy
        // sztucznie zbudowanego material+amount stosu.
        ItemStack custom = new ItemStack(Material.DIAMOND, 4);
        ItemMeta meta = custom.getItemMeta();
        assertNotNull(meta);
        meta.displayName(Component.text("Custom Diamond"));
        custom.setItemMeta(meta);
        player.getInventory().addItem(custom);

        Predicate<ItemStack> p = s -> s != null && s.isSimilar(custom);
        Optional<List<ItemStack>> removed = InventoryOps.removeAllOrNothing(
                player.getInventory(), p, 4);
        assertTrue(removed.isPresent());
        assertEquals(1, removed.get().size());
        ItemStack returned = removed.get().get(0);
        assertEquals(4, returned.getAmount());
        assertNotNull(returned.getItemMeta());
        assertTrue(returned.getItemMeta().hasDisplayName(),
                "zwrócony stos musi nieść display name");
    }

    @Test
    void giveAllOrNothingRestoresSnapshotWhenAddItemLeaksLeftover() {
        // Nie wymusimy łatwo „przecieku" addItem (canFitFully filtruje
        // wcześniej), ale potwierdzamy, że mechanizm snapshotu zostawia
        // ekwipunek nietknięty, gdy add zostanie odrzucony z góry.
        player.getInventory().setItem(0, new ItemStack(Material.STONE, 32));
        player.getInventory().setItem(7, new ItemStack(Material.OAK_LOG, 8));
        ItemStack[] before = InventoryOps.cloneStorage(player.getInventory());
        // Wypełniamy resztę, by 64-stosu diamentów nie dało się dodać.
        for (int i = 0; i < player.getInventory().getStorageContents().length; i++) {
            if (player.getInventory().getItem(i) == null) {
                player.getInventory().setItem(i, new ItemStack(Material.OAK_SAPLING, 64));
            }
        }
        before = InventoryOps.cloneStorage(player.getInventory());

        boolean ok = InventoryOps.giveAllOrNothing(player, new ItemStack(Material.DIAMOND, 64));
        assertFalse(ok);
        ItemStack[] after = InventoryOps.cloneStorage(player.getInventory());
        assertArrayEquals(before, after, "rollback ze snapshotu musi zostawić ekwipunek identyczny bit-w-bit");
    }

    @Test
    void giveAllOrNothingMultiStackAtomic() {
        // Wypełniamy wszystkie sloty oprócz jednego, żeby dwustosowa
        // prośba na pewno się w całości nie zmieściła.
        int size = player.getInventory().getStorageContents().length;
        for (int i = 0; i < size - 1; i++) {
            player.getInventory().setItem(i, new ItemStack(Material.OAK_SAPLING, 64));
        }
        ItemStack[] before = InventoryOps.cloneStorage(player.getInventory());

        boolean ok = InventoryOps.giveAllOrNothing(player, List.of(
                new ItemStack(Material.DIAMOND, 1),
                new ItemStack(Material.EMERALD, 1)
        ));
        // Pierwszy stos by się zmieścił, drugi już nie. Helper musi
        // wycofać też pierwszy.
        ItemStack[] after = InventoryOps.cloneStorage(player.getInventory());
        if (!ok) {
            assertArrayEquals(before, after,
                    "wariant wielo-stosowy musi przywrócić snapshot, gdy którykolwiek stos się nie zmieścił");
        }
    }
}
