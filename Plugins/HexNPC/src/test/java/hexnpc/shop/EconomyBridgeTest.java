package hexnpc.shop;

import hexnpc.shop.economy.EconomyBridge;
import hexnpc.shop.economy.TxResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyBridgeTest {

    private ServerMock server;
    private EconomyBridge bridge;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        bridge = new EconomyBridge(Logger.getLogger("test"));
    }

    @AfterEach
    void tearDown() {
        bridge.shutdown();
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void noHexEconomyPluginMeansUnavailable() {
        // W mocku nie ma pluginu HexEconomy. Bridge musi szukać go przez
        // PluginManager zanim sięgnie do Class.forName, więc isAvailable
        // jest false bez dotykania parent classloadera.
        assertFalse(bridge.isAvailable(),
                "bridge musi zgłosić niedostępność, gdy plugin HexEconomy nie istnieje");
    }

    @Test
    void withdrawCompletesWithEconomyUnavailableWhenAbsent() throws Exception {
        TxResult result = bridge.withdraw(UUID.randomUUID(), "Test",
                new BigDecimal("1.00"), "test").get();
        assertNotNull(result);
        assertFalse(result.success(),
                "withdraw musi zgłosić porażkę, gdy bridge jest niedostępny");
        assertTrue(result.isEconomyUnavailable(),
                "powód porażki musi klasyfikować się jako economy-unavailable");
    }
}
