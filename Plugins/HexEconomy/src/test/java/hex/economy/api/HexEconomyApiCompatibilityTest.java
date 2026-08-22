package hex.economy.api;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HexEconomyApiCompatibilityTest {
    @Test
    void legacyImplementationDoesNotNeedNewAbstractMethods() {
        HexEconomyApi api = new LegacyImplementation();
        assertEquals(new BigDecimal("42.00"), api.getBalance(UUID.randomUUID()));
        assertTrue(api.isCurrencyAvailable(CurrencyType.MONEY));
        assertFalse(api.isCurrencyAvailable(CurrencyType.HEX_COINS));
        assertEquals("CURRENCY_UNAVAILABLE", api.withdraw(UUID.randomUUID(), "x", CurrencyType.HEX_COINS, BigDecimal.ONE, "test").reason());
    }

    static final class LegacyImplementation implements HexEconomyApi {
        public BigDecimal getBalance(UUID u) { return new BigDecimal("42.00"); }
        public EconomyResult deposit(UUID u,String n,BigDecimal a,String r){ return EconomyResult.ok(getBalance(u).add(a)); }
        public EconomyResult withdraw(UUID u,String n,BigDecimal a,String r){ return EconomyResult.ok(getBalance(u).subtract(a)); }
        public EconomyResult setBalance(UUID u,String n,BigDecimal a,String r){ return EconomyResult.ok(a); }
        public boolean has(UUID u,BigDecimal a){ return getBalance(u).compareTo(a)>=0; }
        public String format(BigDecimal a){ return a.toPlainString()+" MONEY"; }
        public String currencyName(){ return "money"; }
    }
}
