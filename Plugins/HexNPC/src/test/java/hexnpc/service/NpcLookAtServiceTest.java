package hexnpc.service;

import hexnpc.HexNpcPlugin;
import hexnpc.model.LookAtSettings;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.render.NpcHandle;
import hexnpc.render.NpcRenderer;
import hexnpc.storage.YamlNpcStorage;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.nio.file.Files;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Look-At-Service: Zielauswahl (naechster Spieler), Reset-Verhalten und die zentrale
 * Zusicherung, dass das Verfolgen die gespeicherte NPC-Location NIE veraendert.
 */
class NpcLookAtServiceTest {

    private ServerMock server;
    private HexNpcPlugin plugin;
    private World world;
    private CapturingRenderer renderer;
    private NpcService npcService;
    private NpcLookAtService lookAtService;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexNpcPlugin.class);

        File tmp = Files.createTempFile("npcs-lookat-", ".yml").toFile();
        tmp.delete();
        YamlNpcStorage storage = new YamlNpcStorage(tmp, plugin.getLogger());
        storage.load();
        renderer = new CapturingRenderer();
        npcService = new NpcService(storage, renderer, plugin::config, plugin.getLogger());
        lookAtService = new NpcLookAtService(plugin, npcService, renderer, plugin::config);

        npcService.create(new NpcId("guard"), new NpcLocation("world", 0, 64, 0, 0f, 0f));
        npcService.setLookAt(new NpcId("guard"), new LookAtSettings(true, 10.0D, 5, true));
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    private PlayerMock addPlayerAt(String name, double x, double y, double z) {
        PlayerMock p = server.addPlayer(name);
        p.teleport(new Location(world, x, y, z, 0f, 0f));
        return p;
    }

    private NpcDefinition guard() {
        return npcService.find(new NpcId("guard")).orElseThrow();
    }

    @Test
    void looksAtPlayerInRangeAndKeepsStoredLocation() {
        PlayerMock player = addPlayerAt("Near", 0, 64, 5); // straight +Z from the NPC
        lookAtService.scanOnce();

        assertEquals(new NpcId("guard"), renderer.lastLookId);
        assertEquals(0.0f, renderer.lastYaw, 1e-2, "+Z -> yaw ~0");
        assertEquals(player.getUniqueId(), lookAtService.currentTarget(new NpcId("guard")));

        // Kernanforderung: gespeicherte Location/Rotation bleibt unveraendert.
        NpcLocation loc = guard().location();
        assertEquals(0.0f, loc.yaw(), 1e-6, "stored yaw unchanged");
        assertEquals(0.0f, loc.pitch(), 1e-6, "stored pitch unchanged");
        assertEquals(0.0D, loc.x(), 1e-9);
        assertEquals(64.0D, loc.y(), 1e-9);
    }

    @Test
    void picksNearestPlayerWhenMultipleInRange() {
        addPlayerAt("Far", 0, 64, 8);
        PlayerMock near = addPlayerAt("Near", 3, 64, 0);
        lookAtService.scanOnce();
        assertEquals(near.getUniqueId(), lookAtService.currentTarget(new NpcId("guard")),
                "naechster Spieler wird gewaehlt");
    }

    @Test
    void resetsToStoredRotationWhenNoPlayerInRange() {
        PlayerMock player = addPlayerAt("Near", 0, 64, 5);
        lookAtService.scanOnce();
        assertEquals(1, renderer.lookCount);

        player.teleport(new Location(world, 0, 64, 500, 0f, 0f)); // out of range
        lookAtService.scanOnce();

        assertEquals(1, renderer.resetCount, "Reset genau einmal beim Verlassen der Range");
        assertNull(lookAtService.currentTarget(new NpcId("guard")), "Ziel geloescht");
    }

    @Test
    void doesNotResetWhenResetDisabled() throws Exception {
        npcService.setLookAt(new NpcId("guard"), new LookAtSettings(true, 10.0D, 5, false));
        PlayerMock player = addPlayerAt("Near", 0, 64, 5);
        lookAtService.scanOnce();
        player.teleport(new Location(world, 0, 64, 500, 0f, 0f));
        lookAtService.scanOnce();
        assertEquals(0, renderer.resetCount, "reset-when-empty=false -> kein resetLook");
    }

    @Test
    void disabledLookAtDoesNothing() throws Exception {
        npcService.setLookAt(new NpcId("guard"), new LookAtSettings(false, 10.0D, 5, true));
        addPlayerAt("Near", 0, 64, 5);
        lookAtService.scanOnce();
        assertEquals(0, renderer.lookCount, "deaktiviert -> kein lookAt");
        assertNull(renderer.lastLookId);
    }

    @Test
    void noPlayerNoInteraction() {
        lookAtService.scanOnce();
        assertEquals(0, renderer.lookCount);
        assertEquals(0, renderer.resetCount);
    }

    /** Renderer-Stub: zeichnet lookAt/resetLook-Aufrufe auf, ohne Pakete zu senden. */
    private static final class CapturingRenderer implements NpcRenderer {
        NpcId lastLookId;
        float lastYaw;
        float lastPitch;
        int lookCount;
        int resetCount;

        @Override
        public void lookAt(NpcId id, float yaw, float pitch) {
            lastLookId = id;
            lastYaw = yaw;
            lastPitch = pitch;
            lookCount++;
        }

        @Override
        public void resetLook(NpcId id) {
            resetCount++;
        }

        @Override
        public NpcHandle spawn(NpcDefinition definition) {
            return new NpcHandle() {
                @Override
                public NpcId id() {
                    return definition.id();
                }

                @Override
                public int entityId() {
                    return 1;
                }
            };
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void despawn(NpcId id) {
        }

        @Override
        public void move(NpcDefinition updated) {
        }

        @Override
        public void rotate(NpcDefinition updated) {
        }

        @Override
        public void showTo(org.bukkit.entity.Player player) {
        }

        @Override
        public void hideFrom(org.bukkit.entity.Player player) {
        }

        @Override
        public Optional<NpcHandle> handle(NpcId id) {
            return Optional.empty();
        }

        @Override
        public Optional<NpcId> lookupByEntityId(int entityId) {
            return Optional.empty();
        }
    }
}
