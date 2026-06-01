package hexabovename;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class MockBukkitEnvironmentSmokeTest {

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

    @Test
    void shouldBootMockServer() {
        assertNotNull(server);
        assertSame(server, MockBukkit.getMock());
    }

    @Test
    void shouldAddPlayerAndWorld() {
        Player player = server.addPlayer("Filip");
        World world = server.addSimpleWorld("test_world");

        assertNotNull(player);
        assertEquals("Filip", player.getName());
        assertNotNull(world);
        assertEquals("test_world", world.getName());
    }
}
