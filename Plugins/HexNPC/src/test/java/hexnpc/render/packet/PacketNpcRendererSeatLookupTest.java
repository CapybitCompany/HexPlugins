package hexnpc.render.packet;

import hexnpc.HexNpcPlugin;
import hexnpc.model.Dialogue;
import hexnpc.model.InteractionSettings;
import hexnpc.model.NpcActions;
import hexnpc.model.NpcAppearance;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.model.NpcPose;
import hexnpc.model.NpcSkin;
import hexnpc.render.NpcHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sitzende NPCs muessen klickbar bleiben: der Client trifft beim Sitzen ggf. das
 * Sitz-Vehicle statt der Player-Entity. Deshalb muessen beide Entity-Ids auf
 * dieselbe {@link NpcId} zeigen und beim Despawn beide entfernt werden.
 *
 * <p>Es werden keine Spieler in Reichweite hinzugefuegt, damit {@code spawn}
 * lediglich die Ids reserviert/mappt, ohne Pakete (und damit PacketEvents) zu
 * benoetigen.
 */
class PacketNpcRendererSeatLookupTest {

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

    private PacketNpcRenderer newRenderer() {
        return new PacketNpcRenderer(plugin, plugin::config, plugin.getLogger());
    }

    private NpcDefinition sittingNpc(String id) {
        return new NpcDefinition(
                new NpcId(id),
                NpcSkin.ofName(id),
                new NpcLocation("world", 0, 64, 0, 0f, 0f),
                InteractionSettings.defaultClick(),
                Dialogue.empty(),
                NpcActions.empty(),
                new NpcAppearance(null, false, NpcPose.SITTING)
        );
    }

    @Test
    void npcSeatAndNameEntityIdsResolveToSameNpc() {
        PacketNpcRenderer renderer = newRenderer();
        NpcId id = new NpcId("sitter");
        NpcHandle handle = renderer.spawn(sittingNpc("sitter"));

        int npcEntityId = handle.entityId();
        int seatEntityId = renderer.seatEntityId(id).orElseThrow();
        int nameEntityId = renderer.nameEntityId(id).orElseThrow();
        assertNotEquals(npcEntityId, seatEntityId, "NPC- und Seat-Id muessen verschieden sein");
        assertNotEquals(npcEntityId, nameEntityId, "NPC- und Name-Id muessen verschieden sein");
        assertNotEquals(seatEntityId, nameEntityId, "Seat- und Name-Id muessen verschieden sein");

        assertEquals(Optional.of(id), renderer.lookupByEntityId(npcEntityId),
                "Klick auf die Player-Entity loest die NPC-Interaktion aus");
        assertEquals(Optional.of(id), renderer.lookupByEntityId(seatEntityId),
                "Klick auf das Sitz-Vehicle loest dieselbe NPC-Interaktion aus");
        assertEquals(Optional.of(id), renderer.lookupByEntityId(nameEntityId),
                "Klick auf die Nameplate loest dieselbe NPC-Interaktion aus");
    }

    @Test
    void despawnRemovesAllEntityIds() {
        PacketNpcRenderer renderer = newRenderer();
        NpcId id = new NpcId("sitter");
        NpcHandle handle = renderer.spawn(sittingNpc("sitter"));
        int npcEntityId = handle.entityId();
        int seatEntityId = renderer.seatEntityId(id).orElseThrow();
        int nameEntityId = renderer.nameEntityId(id).orElseThrow();

        renderer.despawn(id);

        assertTrue(renderer.lookupByEntityId(npcEntityId).isEmpty(), "NPC-Id nach despawn entfernt");
        assertTrue(renderer.lookupByEntityId(seatEntityId).isEmpty(), "Seat-Id nach despawn entfernt");
        assertTrue(renderer.lookupByEntityId(nameEntityId).isEmpty(), "Name-Id nach despawn entfernt");
        assertTrue(renderer.seatEntityId(id).isEmpty(), "kein Handle mehr fuer die NpcId");
        assertTrue(renderer.nameEntityId(id).isEmpty(), "kein Handle mehr fuer die NpcId");
    }

    @Test
    void stopRemovesAllEntityIds() {
        PacketNpcRenderer renderer = newRenderer();
        NpcId id = new NpcId("sitter");
        NpcHandle handle = renderer.spawn(sittingNpc("sitter"));
        int npcEntityId = handle.entityId();
        int seatEntityId = renderer.seatEntityId(id).orElseThrow();
        int nameEntityId = renderer.nameEntityId(id).orElseThrow();

        renderer.stop();

        assertTrue(renderer.lookupByEntityId(npcEntityId).isEmpty(), "NPC-Id nach stop entfernt");
        assertTrue(renderer.lookupByEntityId(seatEntityId).isEmpty(), "Seat-Id nach stop entfernt");
        assertTrue(renderer.lookupByEntityId(nameEntityId).isEmpty(), "Name-Id nach stop entfernt");
    }
}
