package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.model.BazaarOrder;
import hex.auctionbazaar.bazaar.model.OrderSide;
import hex.auctionbazaar.bazaar.model.OrderState;
import hex.auctionbazaar.bazaar.repository.BazaarOrderRepository;
import hex.auctionbazaar.bazaar.service.BazaarOrderService;
import hex.auctionbazaar.bazaar.service.BazaarOrderService.RemoveResult;
import hex.auctionbazaar.testutil.InMemoryDb;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #5: usuwanie wyłącznie własnych, ANULOWANYCH wpisów zleceń.
 * Testujemy czystą decyzję (właściciel/stan) oraz parametryzację zapytania DELETE.
 */
class BazaarOrderRemoveTest {

    private static BazaarOrder order(UUID owner, OrderState state) {
        return new BazaarOrder(7L, owner, "Owner", "diamond", OrderSide.BUY,
                10L, 10L, new BigDecimal("5"), new BigDecimal("50"),
                state, 0L, 0L, null);
    }

    @Test
    void ownCancelledIsRemovable() {
        UUID me = UUID.randomUUID();
        assertEquals(RemoveResult.OK,
                BazaarOrderService.decideRemove(order(me, OrderState.CANCELLED), me));
    }

    @Test
    void foreignOrderIsNotOwner() {
        assertEquals(RemoveResult.NOT_OWNER,
                BazaarOrderService.decideRemove(order(UUID.randomUUID(), OrderState.CANCELLED),
                        UUID.randomUUID()));
    }

    @Test
    void missingOrderIsNotFound() {
        assertEquals(RemoveResult.NOT_FOUND,
                BazaarOrderService.decideRemove(null, UUID.randomUUID()));
    }

    @Test
    void activeOrPartiallyFilledNeverRemovable() {
        UUID me = UUID.randomUUID();
        assertEquals(RemoveResult.NOT_CANCELLED,
                BazaarOrderService.decideRemove(order(me, OrderState.ACTIVE), me));
        assertEquals(RemoveResult.NOT_CANCELLED,
                BazaarOrderService.decideRemove(order(me, OrderState.PARTIALLY_FILLED), me));
        assertEquals(RemoveResult.NOT_CANCELLED,
                BazaarOrderService.decideRemove(order(me, OrderState.FILLED), me));
    }

    @Test
    void resultValuesAreStable() {
        // Kompletny zestaw wartości wyniku dla GUI (dołączono serwerowe NO_PERMISSION - punkt #5).
        assertEquals(6, RemoveResult.values().length);
        // NO_PERMISSION musi istnieć: usuwanie wpisu jest chronione serwerowo permisją.
        assertEquals(RemoveResult.NO_PERMISSION, RemoveResult.valueOf("NO_PERMISSION"));
    }

    @Test
    void deleteSqlIsParameterizedAndGuardedByCancelled() {
        InMemoryDb db = new InMemoryDb();
        BazaarOrderRepository repo = new BazaarOrderRepository(db);
        UUID owner = UUID.randomUUID();

        boolean ok = repo.deleteCancelledByOwner(7L, owner);
        assertTrue(ok, "InMemoryDb domyślnie zwraca 1 wiersz");

        List<InMemoryDb.Op> ops = db.operations();
        InMemoryDb.Op last = ops.get(ops.size() - 1);
        assertTrue(last.sql().startsWith("DELETE FROM "), "musi być DELETE");
        assertTrue(last.sql().contains("WHERE id=? AND owner_uuid=? AND state=?"),
                "zapytanie musi być parametryzowane");
        // Guard state=CANCELLED chroni ACTIVE/PARTIALLY_FILLED.
        assertEquals(OrderState.CANCELLED.name(), last.params().get(2));
        assertEquals(7L, last.params().get(0));
        assertEquals(owner.toString(), last.params().get(1));
    }
}
