package hexnpc.event;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EventGuyMenuServiceTest {

    private ServerMock server;
    private EventGuyMenuService service;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        EventGuyMenuService.setInventoryFactory((holder, size, title) ->
                Bukkit.createInventory(holder, size, LegacyComponentSerializer.legacySection().serialize(title)));
        service = new EventGuyMenuService(() -> null);
    }

    @AfterEach
    void tearDown() {
        EventGuyMenuService.setInventoryFactory(null);
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void opensPersonalMenuWithTicketShopAndEventInfoButtons() {
        PlayerMock player = server.addPlayer("Eventer");

        service.open(player, "event_tickets", "event", "&5Eventy");

        Inventory inventory = player.getOpenInventory().getTopInventory();
        EventGuyMenuHolder holder = assertInstanceOf(EventGuyMenuHolder.class, inventory.getHolder());
        assertEquals("event_tickets", holder.shopId());
        assertEquals("event", holder.eventCommand());
        assertNotNull(inventory.getItem(EventGuyMenuService.TICKET_SLOT));
        assertEquals(Material.PAPER, inventory.getItem(EventGuyMenuService.TICKET_SLOT).getType());
        assertNotNull(inventory.getItem(EventGuyMenuService.EVENT_SLOT));
        assertEquals(Material.BOOK, inventory.getItem(EventGuyMenuService.EVENT_SLOT).getType());
    }
}
