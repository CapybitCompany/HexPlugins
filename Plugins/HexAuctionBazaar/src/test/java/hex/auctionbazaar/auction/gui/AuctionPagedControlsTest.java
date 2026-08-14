package hex.auctionbazaar.auction.gui;

import hex.auctionbazaar.config.ConfigLoader;
import hex.auctionbazaar.config.PluginConfig;
import hex.auctionbazaar.gui.GuiHolder;
import hex.auctionbazaar.util.MessageFactory;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #7: pasek nawigacji widoków stronicowanych.
 *  - „Wróć” to BARRIER i wykonuje akcję powrotu,
 *  - poprzednia/następna aktywne tylko gdy strona istnieje.
 */
class AuctionPagedControlsTest {

    private ServerMock server;
    private PluginConfig config;
    private MessageFactory messages;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        Path tmp = Files.createTempDirectory("hexab-nav");
        YamlConfiguration main = new YamlConfiguration();
        main.loadFromString("");
        config = ConfigLoader.load(tmp.toFile(), main, Logger.getLogger("test"));
        messages = new MessageFactory(() -> config.messages(), () -> config.prefix());
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private Inventory inv() {
        GuiHolder holder = new GuiHolder(GuiHolder.Kind.AUCTION_CLAIMS);
        Inventory inv = server.createInventory(holder, 54);
        holder.bindInventory(inv);
        return inv;
    }

    @Test
    void backButtonIsHeadAndRunsBackAction() {
        Inventory inv = (Inventory) inv();
        GuiHolder holder = (GuiHolder) inv.getHolder();
        AtomicBoolean back = new AtomicBoolean(false);
        AuctionPagedControls.render(inv, holder, config.auction(), messages, 0, 3, 91,
                "auction.gui.claims-page-info", p -> {}, () -> back.set(true));

        int backSlot = config.auction().pagedSlotBack();
        assertEquals(Material.PLAYER_HEAD, inv.getItem(backSlot).getType());
        Player player = server.addPlayer("Nav");
        holder.actionAt(backSlot).run(new GuiHolder.ClickContext(holder, player, backSlot, false, false));
        assertTrue(back.get(), "kliknięcie Wróć wykonuje akcję powrotu");
    }

    @Test
    void firstPageDisablesPrevEnablesNext() {
        Inventory inv = inv();
        GuiHolder holder = (GuiHolder) inv.getHolder();
        AuctionPagedControls.render(inv, holder, config.auction(), messages, 0, 3, 91,
                "auction.gui.claims-page-info", p -> {}, () -> {});
        assertEquals(Material.PLAYER_HEAD, inv.getItem(config.auction().pagedSlotPrevPage()).getType());
        assertEquals(Material.PLAYER_HEAD, inv.getItem(config.auction().pagedSlotNextPage()).getType());
        assertNull(holder.actionAt(config.auction().pagedSlotPrevPage()), "brak akcji prev na 1. stronie");
        assertNotNull(holder.actionAt(config.auction().pagedSlotNextPage()));
    }

    @Test
    void middlePageEnablesBoth() {
        Inventory inv = inv();
        GuiHolder holder = (GuiHolder) inv.getHolder();
        AtomicInteger target = new AtomicInteger(-1);
        AuctionPagedControls.render(inv, holder, config.auction(), messages, 1, 3, 91,
                "auction.gui.claims-page-info", target::set, () -> {});
        assertEquals(Material.PLAYER_HEAD, inv.getItem(config.auction().pagedSlotPrevPage()).getType());
        assertEquals(Material.PLAYER_HEAD, inv.getItem(config.auction().pagedSlotNextPage()).getType());
        Player player = server.addPlayer("Nav");
        holder.actionAt(config.auction().pagedSlotNextPage())
                .run(new GuiHolder.ClickContext(holder, player,
                        config.auction().pagedSlotNextPage(), false, false));
        assertEquals(2, target.get(), "następna otwiera page+1");
    }

    @Test
    void lastPageDisablesNext() {
        Inventory inv = inv();
        GuiHolder holder = (GuiHolder) inv.getHolder();
        AuctionPagedControls.render(inv, holder, config.auction(), messages, 2, 3, 91,
                "auction.gui.claims-page-info", p -> {}, () -> {});
        assertEquals(Material.PLAYER_HEAD, inv.getItem(config.auction().pagedSlotPrevPage()).getType());
        assertEquals(Material.PLAYER_HEAD, inv.getItem(config.auction().pagedSlotNextPage()).getType());
        assertNull(holder.actionAt(config.auction().pagedSlotNextPage()), "brak akcji next na ostatniej");
    }
}
