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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UX von {@code /hexnpc glow <id> <on|off> [color]} inkl. Farb-Validierung und Tab-Complete.
 */
class HexNpcGlowCommandTest {

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
        admin.setOp(true);
        plugin.npcService().create(new NpcId("guard"), new NpcLocation("world", 0, 64, 0, 0f, 0f));
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
        return plugin.npcService().find(new NpcId("guard")).orElseThrow();
    }

    @Test
    void glowOnWithColorPersistsBoth() {
        run("glow", "guard", "on", "gold");
        assertTrue(npc().appearance().glow());
        assertEquals("gold", npc().appearance().glowColor());
    }

    @Test
    void glowOnWithoutColorLeavesColorUnset() {
        run("glow", "guard", "on");
        assertTrue(npc().appearance().glow());
        assertNull(npc().appearance().glowColor(), "keine Farbe angegeben -> weiss (null)");
    }

    @Test
    void invalidColorIsRejectedAndDoesNotChangeState() {
        run("glow", "guard", "on", "chartreuse");
        assertFalse(npc().appearance().glow(), "ungueltige Farbe -> kein Update angewendet");
        assertNull(npc().appearance().glowColor());
    }

    @Test
    void glowOffKeepsWorking() {
        run("glow", "guard", "on", "red");
        run("glow", "guard", "off");
        assertFalse(npc().appearance().glow());
    }

    @Test
    void tabCompleteOffersColorsAtFourthArg() {
        Command cmd = plugin.getCommand("hexnpc");
        List<String> completions = command.onTabComplete(
                admin, cmd, "hexnpc", new String[]{"glow", "guard", "on", "go"});
        assertTrue(completions.contains("gold"), "Farb-Tab-Complete muss 'gold' enthalten");
        assertFalse(completions.contains("red"), "Prefix 'go' filtert 'red' heraus");
    }
}
