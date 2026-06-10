package hexnpc;

import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.service.NpcService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexNpcPluginSmokeTest {

    private ServerMock server;
    private HexNpcPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexNpcPlugin.class);
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void shouldLoadAndExposeServices() {
        assertNotNull(plugin);
        assertNotNull(plugin.config(), "config should be loaded");
        assertNotNull(plugin.npcService(), "NpcService should be initialized");
        assertNotNull(plugin.actionRegistry(), "NpcActionRegistry should be initialized");
        assertNotNull(plugin.dialogueService(), "DialogueService should be initialized");
        assertNotNull(plugin.interactionService(), "NpcInteractionService should be initialized");
        assertNotNull(plugin.proximityService(), "NpcProximityService should be initialized");
        assertNotNull(plugin.renderer(), "renderer should be initialized");
        assertNotNull(plugin.hexCoreBridge(), "HexCoreBridge should be initialized");
    }

    @Test
    void shouldRegisterBuiltinActions() {
        var registry = plugin.actionRegistry();
        assertTrue(registry.resolve("message").isPresent());
        assertTrue(registry.resolve("console-command").isPresent());
        assertTrue(registry.resolve("player-command").isPresent());
        assertFalse(registry.resolve("nonexistent").isPresent());
    }

    @Test
    void shouldCreateListAndRemoveNpcViaService() throws Exception {
        NpcService svc = plugin.npcService();
        NpcId id = new NpcId("test-npc");
        NpcLocation loc = new NpcLocation("world", 0.5, 65.0, 0.5, 0.0f, 0.0f);

        NpcDefinition created = svc.create(id, loc);
        assertEquals(id, created.id());
        assertEquals(1, svc.list().size());

        Optional<NpcDefinition> looked = svc.find(id);
        assertTrue(looked.isPresent());
        assertEquals("world", looked.get().location().world());

        assertTrue(svc.remove(id));
        assertEquals(0, svc.list().size());
    }

    @Test
    void shouldRunHexnpcCommandWithoutErrors() {
        Player op = server.addPlayer("OpUser");
        op.setOp(true);
        // list with no NPCs
        op.performCommand("hexnpc list");
        // create then list
        op.performCommand("hexnpc create greeter");
        op.performCommand("hexnpc list");
        // unknown subcommand renders usage, not error
        op.performCommand("hexnpc whatever");
        // reload
        op.performCommand("hexnpc reload");
        // After reload, npcService must be re-initialized
        assertNotNull(plugin.npcService());
    }
}
