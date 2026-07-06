package hexnpc;

import hexnpc.model.Dialogue;
import hexnpc.model.InteractionSettings;
import hexnpc.model.NpcActions;
import hexnpc.model.NpcAppearance;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.model.NpcPose;
import hexnpc.model.NpcSkin;
import hexnpc.storage.YamlNpcStorage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistenz von {@link NpcAppearance} (Nickname/Glow/Pose) inkl. Rueckwaerts-
 * kompatibilitaet: alte npcs.yml ohne appearance-Block muessen weiter laden.
 */
class NpcAppearanceStorageTest {

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
    void newStructureRoundTrips() throws Exception {
        File tmp = Files.createTempFile("npcs-appearance-", ".yml").toFile();
        tmp.delete();
        YamlNpcStorage storage = new YamlNpcStorage(tmp, plugin.getLogger());
        storage.load();

        NpcDefinition def = new NpcDefinition(
                new NpcId("king"),
                new NpcSkin("Notch", "val", "sig"),
                new NpcLocation("world", 1, 64, 2, 0f, 0f),
                InteractionSettings.defaultClick(),
                Dialogue.empty(),
                NpcActions.empty(),
                new NpcAppearance("&6&lKról", true, NpcPose.SITTING)
        );
        storage.save(def);

        YamlNpcStorage reloaded = new YamlNpcStorage(tmp, plugin.getLogger());
        reloaded.load();
        NpcDefinition read = reloaded.find(new NpcId("king")).orElseThrow();
        assertEquals("&6&lKról", read.appearance().displayName());
        assertTrue(read.appearance().glow());
        assertEquals(NpcPose.SITTING, read.appearance().pose());
        // Skin-Quelle bleibt unabhaengig erhalten.
        assertEquals("Notch", read.skin().name());
    }

    @Test
    void appearanceDefaultsWhenBrandNew() throws Exception {
        File tmp = Files.createTempFile("npcs-appearance-def-", ".yml").toFile();
        tmp.delete();
        YamlNpcStorage storage = new YamlNpcStorage(tmp, plugin.getLogger());
        storage.load();

        NpcDefinition def = new NpcDefinition(
                new NpcId("plain"),
                NpcSkin.ofName("plain"),
                new NpcLocation("world", 0, 64, 0, 0f, 0f),
                InteractionSettings.defaultClick(),
                Dialogue.empty(),
                NpcActions.empty()
        );
        storage.save(def);

        YamlNpcStorage reloaded = new YamlNpcStorage(tmp, plugin.getLogger());
        reloaded.load();
        NpcDefinition read = reloaded.find(new NpcId("plain")).orElseThrow();
        assertFalse(read.appearance().glow(), "Glow-Default ist false");
        assertEquals(NpcPose.STANDING, read.appearance().pose(), "Pose-Default ist STANDING");
    }

    @Test
    void legacyConfigWithoutAppearanceMigratesSkinNameAsNickname() throws Exception {
        File tmp = writeLegacyNpc("notch", true);

        YamlNpcStorage storage = new YamlNpcStorage(tmp, plugin.getLogger());
        storage.load();
        NpcDefinition read = storage.find(new NpcId("shopkeeper")).orElseThrow();

        // Alter sichtbarer Name kam aus skin.name -> als Nickname migriert.
        assertEquals("Notch", read.appearance().displayName(),
                "Legacy-Config ohne appearance-Block behaelt den bisherigen sichtbaren Namen");
        // Skin-Quelle ebenfalls erhalten.
        assertEquals("Notch", read.skin().name());
        assertFalse(read.appearance().glow());
        assertEquals(NpcPose.STANDING, read.appearance().pose());
    }

    @Test
    void modernAppearanceBlockWithoutDisplayNameDoesNotResurrectSkinName() throws Exception {
        // appearance-Block vorhanden (z.B. nach /hexnpc name <id> clear), skin.name = Notch.
        File tmp = writeLegacyNpc("cleared", true);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(tmp);
        var appearance = yaml.getConfigurationSection("npcs.shopkeeper").createSection("appearance");
        appearance.set("glow", true);
        appearance.set("pose", "sleeping");
        // display-name absichtlich weggelassen.
        yaml.save(tmp);

        YamlNpcStorage storage = new YamlNpcStorage(tmp, plugin.getLogger());
        storage.load();
        NpcDefinition read = storage.find(new NpcId("shopkeeper")).orElseThrow();
        assertNull(read.appearance().displayName(),
                "vorhandener appearance-Block ohne display-name -> kein Nick (Fallback auf Id im Renderer)");
        assertTrue(read.appearance().glow());
        assertEquals(NpcPose.SLEEPING, read.appearance().pose());
    }

    private File writeLegacyNpc(String skinName, boolean withSkinName) throws Exception {
        File tmp = Files.createTempFile("npcs-legacy-appearance-", ".yml").toFile();
        YamlConfiguration legacy = new YamlConfiguration();
        var npcs = legacy.createSection("npcs");
        var npc = npcs.createSection("shopkeeper");
        var skin = npc.createSection("skin");
        if (withSkinName) {
            skin.set("name", "Notch");
        }
        var loc = npc.createSection("location");
        loc.set("world", "world");
        loc.set("x", 0);
        loc.set("y", 64);
        loc.set("z", 0);
        loc.set("yaw", 0);
        loc.set("pitch", 0);
        var interaction = npc.createSection("interaction");
        interaction.set("click", true);
        legacy.save(tmp);
        return tmp;
    }
}
