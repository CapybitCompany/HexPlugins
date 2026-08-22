package hexnpc;

import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.model.NpcPose;
import hexnpc.model.NpcSkin;
import hexnpc.service.NpcService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-Updates fuer Nickname/Glow/Pose und die Kernanforderung: ein Skin-Wechsel
 * veraendert den sichtbaren Nickname nicht.
 */
class NpcAppearanceServiceTest {

    private ServerMock server;
    private HexNpcPlugin plugin;
    private NpcService service;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        plugin = MockBukkit.load(HexNpcPlugin.class);
        service = plugin.npcService();
        service.create(new NpcId("shopkeeper"), new NpcLocation("world", 0, 64, 0, 0f, 0f));
    }

    @AfterEach
    void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    void defaultsAfterCreate() {
        NpcDefinition def = service.find(new NpcId("shopkeeper")).orElseThrow();
        assertFalse(def.appearance().glow());
        assertEquals(NpcPose.STANDING, def.appearance().pose());
        assertFalse(def.appearance().hasDisplayName(), "frisch erstellt: kein expliziter Nick");
    }

    @Test
    void setDisplayNameGlowAndPosePersist() throws Exception {
        service.setDisplayName(new NpcId("shopkeeper"), "&6Sklepikarz");
        service.setGlow(new NpcId("shopkeeper"), true);
        service.setPose(new NpcId("shopkeeper"), NpcPose.SITTING);

        NpcDefinition def = service.find(new NpcId("shopkeeper")).orElseThrow();
        assertEquals("&6Sklepikarz", def.appearance().displayName());
        assertTrue(def.appearance().glow());
        assertEquals(NpcPose.SITTING, def.appearance().pose());
    }

    @Test
    void clearDisplayNameResetsToNull() throws Exception {
        service.setDisplayName(new NpcId("shopkeeper"), "&6Sklepikarz");
        service.setDisplayName(new NpcId("shopkeeper"), null);
        NpcDefinition def = service.find(new NpcId("shopkeeper")).orElseThrow();
        assertNull(def.appearance().displayName(), "leerer Nick -> Fallback auf Id");
    }

    @Test
    void changingSkinPreservesNickname() throws Exception {
        service.setDisplayName(new NpcId("shopkeeper"), "&6&lKról");
        // Skin-Wechsel wie /hexnpc skin <id> <name> (raw-Textur, damit kein Netzwerk noetig).
        service.setSkin(new NpcId("shopkeeper"), new NpcSkin("Notch", "val", "sig"));

        NpcDefinition def = service.find(new NpcId("shopkeeper")).orElseThrow();
        assertEquals("&6&lKról", def.appearance().displayName(),
                "Skin-Wechsel darf den sichtbaren Nickname nicht ueberschreiben");
        assertEquals("Notch", def.skin().name(), "Skin-Quelle wurde aktualisiert");
    }
}
