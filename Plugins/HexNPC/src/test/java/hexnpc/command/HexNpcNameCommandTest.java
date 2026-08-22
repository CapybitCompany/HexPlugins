package hexnpc.command;

import hexnpc.HexNpcPlugin;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import org.bukkit.command.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * UX der Nickname-Befehle: neue Syntax {@code name set/clear <id>} und die weiterhin
 * gueltige Legacy-Syntax {@code name <id> <nick>/clear}.
 */
class HexNpcNameCommandTest {

    private ServerMock server;
    private HexNpcPlugin plugin;
    private HexNpcCommand command;
    private PlayerMock admin;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexNpcPlugin.class);
        command = new HexNpcCommand(plugin);
        admin = server.addPlayer("Admin");
        admin.setOp(true); // hexnpc.admin default: op
        plugin.npcService().create(new NpcId("shopkeeper"), new NpcLocation("world", 0, 64, 0, 0f, 0f));
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private void run(String... args) {
        Command cmd = plugin.getCommand("hexnpc");
        command.onCommand(admin, cmd, "hexnpc", args);
    }

    private NpcDefinition npc() {
        return plugin.npcService().find(new NpcId("shopkeeper")).orElseThrow();
    }

    @Test
    void nameSetAssignsColoredNickname() {
        run("name", "set", "shopkeeper", "&6&lKról");
        assertEquals("&6&lKról", npc().appearance().displayName());
    }

    @Test
    void nameSetJoinsMultipleWords() {
        run("name", "set", "shopkeeper", "&6Wielki", "Sprzedawca");
        assertEquals("&6Wielki Sprzedawca", npc().appearance().displayName());
    }

    @Test
    void nameClearRemovesNickname() {
        run("name", "set", "shopkeeper", "&6&lKról");
        assertEquals("&6&lKról", npc().appearance().displayName());

        run("name", "clear", "shopkeeper");
        assertNull(npc().appearance().displayName(), "Nick geloescht -> Fallback auf Id");
        assertFalse(npc().appearance().hasDisplayName());
    }

    @Test
    void legacyNameSyntaxStillWorks() {
        run("name", "shopkeeper", "&6Sklepikarz");
        assertEquals("&6Sklepikarz", npc().appearance().displayName());

        run("name", "shopkeeper", "clear");
        assertNull(npc().appearance().displayName());
    }
}
