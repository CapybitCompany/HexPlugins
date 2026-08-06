package hex.auctionbazaar.bazaar.gui;

import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.config.BazaarItemConfig;
import hex.auctionbazaar.gui.GuiHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #4: auto-refresh w miejscu.
 *  - maks. jedna sesja na gracza (brak akumulacji przy wielokrotnym register),
 *  - sweep aktualizuje przez updater i NIE otwiera ponownie inventory,
 *  - przełączenie widoku usuwa sesję i unieważnia stary uchwyt (veraltete).
 */
class BazaarAutoRefreshTickerTest {

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer("Viewer");
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private BazaarConfig cfg(boolean autoRefresh) {
        return new BazaarConfig(
                true, true, 14, 0L, 6000,
                new BazaarConfig.Pricing(new BigDecimal("0.5"), new BigDecimal("10000"),
                        new BigDecimal("5"), new BigDecimal("5")),
                "&8&lRynek", "&8%display%", "&8Q", "&8O", "&8Zlecenie", "GRAY_STAINED_GLASS_PANE",
                List.of(1L, 64L, 576L),
                autoRefresh, 20,
                Map.<String, BazaarConfig.CategoryConfig>of(),
                "hexbazaar.open", "hexbazaar.buy", "hexbazaar.sell",
                "hexbazaar.orders", "hexbazaar.order.create.buy",
                "hexbazaar.order.create.sell", "hexbazaar.order.cancel", "hexbazaar.admin",
                Map.<String, BazaarItemConfig>of());
    }

    private Inventory openGui() {
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.BAZAAR_MAIN);
        Inventory inv = server.createInventory(holder, 54);
        holder.bindInventory(inv);
        player.openInventory(inv);
        return inv;
    }

    @Test
    void disabledAutoRefreshRegistersNothing() {
        BazaarAutoRefreshTicker ticker = new BazaarAutoRefreshTicker(plugin, () -> cfg(false));
        Inventory inv = openGui();
        assertNull(ticker.register(player, (GuiHolder) inv.getHolder(), inv, h -> {}));
        assertEquals(0, ticker.sessionCount());
    }

    @Test
    void registerReplacesForOneSessionPerPlayer() {
        BazaarAutoRefreshTicker ticker = new BazaarAutoRefreshTicker(plugin, () -> cfg(true));
        Inventory inv = openGui();
        GuiHolder holder = (GuiHolder) inv.getHolder();
        ticker.register(player, holder, inv, h -> {});
        ticker.register(player, holder, inv, h -> {});
        ticker.register(player, holder, inv, h -> {});
        assertEquals(1, ticker.sessionCount(), "wielokrotny register nie mnoży sesji");
    }

    @Test
    void sweepUpdatesInPlaceWithoutReopening() {
        BazaarAutoRefreshTicker ticker = new BazaarAutoRefreshTicker(plugin, () -> cfg(true));
        Inventory inv = openGui();
        GuiHolder holder = (GuiHolder) inv.getHolder();
        AtomicInteger updates = new AtomicInteger();
        AtomicBoolean currentAtCall = new AtomicBoolean(false);
        ticker.register(player, holder, inv, h -> {
            updates.incrementAndGet();
            currentAtCall.set(h.isCurrent());
        });

        ticker.sweep();
        assertEquals(1, updates.get(), "sweep wywołał updater");
        assertTrue(currentAtCall.get(), "sesja aktualna podczas aktualizacji");
        // NIE otwarto nowego inventory - wciąż to samo okno.
        assertSame(inv, player.getOpenInventory().getTopInventory());
        assertEquals(1, ticker.sessionCount());
    }

    @Test
    void switchingViewDropsSessionAndInvalidatesStaleHandle() {
        BazaarAutoRefreshTicker ticker = new BazaarAutoRefreshTicker(plugin, () -> cfg(true));
        Inventory inv = openGui();
        GuiHolder holder = (GuiHolder) inv.getHolder();
        AtomicReference<BazaarAutoRefreshTicker.RefreshHandle> captured = new AtomicReference<>();
        AtomicInteger updates = new AtomicInteger();
        ticker.register(player, holder, inv, h -> {
            captured.set(h);
            updates.incrementAndGet();
        });

        ticker.sweep();
        assertEquals(1, updates.get());
        assertTrue(captured.get().isCurrent());

        // Gracz przełącza się na inne GUI (nowe okno).
        openGui();
        ticker.sweep();
        assertEquals(1, updates.get(), "stary widok nie jest już aktualizowany");
        assertEquals(0, ticker.sessionCount(), "sesja usunięta po przełączeniu");
        assertFalse(captured.get().isCurrent(), "veraltete uchwyt unieważniony (async result verworfen)");
    }

    @Test
    void outOfOrderOlderResponseIsIgnored() {
        // Punkt #10: dwa żądania tej samej sesji; starsza odpowiedź (wraca po nowszej) nie może
        // nadpisać widoku. Generacja żądania w Handle zapewnia, że tylko najnowsze jest aktualne.
        BazaarAutoRefreshTicker ticker = new BazaarAutoRefreshTicker(plugin, () -> cfg(true));
        Inventory inv = openGui();
        GuiHolder holder = (GuiHolder) inv.getHolder();
        java.util.List<BazaarAutoRefreshTicker.RefreshHandle> handles = new java.util.ArrayList<>();
        ticker.register(player, holder, inv, handles::add);

        ticker.sweep();   // żądanie 1 -> handle #1 (generacja 1)
        ticker.sweep();   // żądanie 2 -> handle #2 (generacja 2)
        assertEquals(2, handles.size());
        assertFalse(handles.get(0).isCurrent(), "starsza odpowiedź (out-of-order) odrzucona");
        assertTrue(handles.get(1).isCurrent(), "najnowsza odpowiedź nadal aktualna");
    }

    @Test
    void quitOrCloseUnregistersPlayer() {
        BazaarAutoRefreshTicker ticker = new BazaarAutoRefreshTicker(plugin, () -> cfg(true));
        Inventory inv = openGui();
        ticker.register(player, (GuiHolder) inv.getHolder(), inv, h -> {});
        assertEquals(1, ticker.sessionCount());
        ticker.unregisterPlayer(player.getUniqueId());
        assertEquals(0, ticker.sessionCount());
    }
}
