package hexnpc.shop;

import hexnpc.shop.economy.EconomyBridge;
import hexnpc.shop.economy.TxResult;
import hexnpc.shop.model.ShopCurrency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regresja 1.1.1: MONEY nigdy nie może wejść w optional multi-currency surface. */
class EconomyBridgeMoneyIsolationTest {

    private final LegacyOnlyBridge bridge = new LegacyOnlyBridge();

    @AfterEach
    void tearDown() {
        bridge.shutdown();
    }

    @Test
    void moneyOverloadsAlwaysDelegateToLegacyMethods() throws Exception {
        assertTrue(bridge.isAvailable(ShopCurrency.MONEY));
        assertEquals(1, bridge.legacyAvailabilityCalls);

        assertEquals("LEGACY:12.34", bridge.format(ShopCurrency.MONEY, new BigDecimal("12.34")));
        assertEquals(1, bridge.legacyFormatCalls);

        assertEquals("LegacyMoney", bridge.currencyName(ShopCurrency.MONEY));
        assertEquals(1, bridge.legacyCurrencyNameCalls);

        TxResult withdrawn = bridge.withdraw(UUID.randomUUID(), "Player", ShopCurrency.MONEY,
                new BigDecimal("5.00"), "test").get();
        TxResult deposited = bridge.deposit(UUID.randomUUID(), "Player", ShopCurrency.MONEY,
                new BigDecimal("6.00"), "test").get();

        assertTrue(withdrawn.success());
        assertTrue(deposited.success());
        assertEquals(1, bridge.legacyWithdrawCalls);
        assertEquals(1, bridge.legacyDepositCalls);
    }

    private static final class LegacyOnlyBridge extends EconomyBridge {
        int legacyAvailabilityCalls;
        int legacyFormatCalls;
        int legacyCurrencyNameCalls;
        int legacyWithdrawCalls;
        int legacyDepositCalls;

        LegacyOnlyBridge() {
            super(Logger.getLogger("legacy-only"));
        }

        @Override
        public boolean isAvailable() {
            legacyAvailabilityCalls++;
            return true;
        }

        @Override
        public String format(BigDecimal value) {
            legacyFormatCalls++;
            return "LEGACY:" + value.toPlainString();
        }

        @Override
        public String currencyName() {
            legacyCurrencyNameCalls++;
            return "LegacyMoney";
        }

        @Override
        public CompletableFuture<TxResult> withdraw(UUID uuid, String playerName,
                                                    BigDecimal amount, String reason) {
            legacyWithdrawCalls++;
            return CompletableFuture.completedFuture(TxResult.ok(BigDecimal.ZERO));
        }

        @Override
        public CompletableFuture<TxResult> deposit(UUID uuid, String playerName,
                                                   BigDecimal amount, String reason) {
            legacyDepositCalls++;
            return CompletableFuture.completedFuture(TxResult.ok(BigDecimal.ZERO));
        }
    }
}
