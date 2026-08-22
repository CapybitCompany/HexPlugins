package hex.economy.xconomy;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class XConomyReflectionBackendTest {
    @Test
    void mapsXConomyResultCodesAndUsesIntegerOnlyBalances() throws Exception {
        UUID uuid = UUID.randomUUID();
        var backend = XConomyReflectionBackend.create(getClass().getClassLoader());
        assertTrue(backend.deposit(uuid, "Tester", 10, "test").success());
        assertEquals(5, backend.withdraw(uuid, "Tester", 5, "test").balance());
        assertEquals("NOT_ENOUGH_FUNDS", backend.withdraw(uuid, "Tester", 99, "test").reason());
        assertEquals(7, backend.set(uuid, "Tester", 7, "test").balance());
        assertEquals(uuid, backend.findAccountByName("tester").orElseThrow().uuid());
    }

    @Test
    void rejectsFractionalBalanceAlreadyPresentInXConomy() throws Exception {
        UUID uuid = UUID.randomUUID();
        var backend = XConomyReflectionBackend.create(getClass().getClassLoader());
        backend.deposit(uuid, "Fractional", 2, "test");
        var api = new me.yic.xconomy.api.XConomyAPI();
        api.getPlayerData(uuid).setBalance(new BigDecimal("2.5"));
        assertThrows(IllegalStateException.class, () -> backend.getBalance(uuid));
        assertEquals("PROVIDER_ERROR", backend.withdraw(uuid, "Fractional", 1, "test").reason());
        assertEquals(new BigDecimal("2.5"), api.getPlayerData(uuid).getBalance(), "invalid fractional balance must not be mutated");
    }
}
