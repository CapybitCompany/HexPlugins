package hexnpc.render.packet;

import hexnpc.model.Dialogue;
import hexnpc.model.InteractionSettings;
import hexnpc.model.NpcActions;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.model.NpcSkin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mojang-Usernames sind auf 16 Zeichen limitiert und akzeptieren das in NpcId
 * erlaubte Minuszeichen nicht. Diese Tests sichern den Sanitizer ab, der den
 * Profilnamen für UserProfile aufbereitet, ohne die NpcId-Regeln zu brechen.
 */
class PacketNpcRendererProfileNameTest {

    @Test
    void shortIdIsReturnedUnchanged() {
        NpcDefinition def = npc("greeter", null);
        assertEquals("greeter", PacketNpcRenderer.profileName(def));
    }

    @Test
    void longIdIsTruncatedToSixteenChars() {
        // NpcId erlaubt bis 32 Zeichen — Profilname darf max. 16 sein.
        NpcDefinition def = npc("abcdefghij_klmno_pqrs", null);
        String name = PacketNpcRenderer.profileName(def);
        assertTrue(name.length() <= 16, "profile name must be <= 16 chars, got " + name);
        assertEquals("abcdefghij_klmno", name);
    }

    @Test
    void hyphenInIdIsReplacedWithUnderscore() {
        // '-' ist in NpcId zulässig, aber kein gültiges Mojang-Username-Zeichen.
        NpcDefinition def = npc("event-greeter", null);
        assertEquals("event_greeter", PacketNpcRenderer.profileName(def));
    }

    @Test
    void skinNameDoesNotAffectProfileName() {
        // Profilname folgt AUSSCHLIESSLICH der NpcId, niemals der Skin-Quelle.
        NpcDefinition def = npc("greeter", NpcSkin.ofName("Notch"));
        assertEquals("greeter", PacketNpcRenderer.profileName(def));
    }

    @Test
    void changingSkinKeepsProfileNameStable() {
        // Kernanforderung: /hexnpc skin <id> <name> darf den Fake-Player-Profilnamen
        // nicht mehr aendern — von Notch zu Herobrine bleibt der Profilname gleich.
        NpcDefinition base = npc("greeter", NpcSkin.ofName("Notch"));
        String before = PacketNpcRenderer.profileName(base);
        String afterNotch = PacketNpcRenderer.profileName(base.withSkin(NpcSkin.ofName("Notch")));
        String afterHerobrine = PacketNpcRenderer.profileName(base.withSkin(NpcSkin.ofName("Herobrine")));
        assertEquals(before, afterNotch);
        assertEquals(before, afterHerobrine);
        assertEquals("greeter", afterHerobrine);
    }

    @Test
    void profileNameIgnoresLongSkinNameAndFollowsId() {
        NpcDefinition def = npc("greeter", NpcSkin.ofName("very-long-skin-name-needs-cut"));
        assertEquals("greeter", PacketNpcRenderer.profileName(def),
                "langer Skin-Name darf den Profilnamen nicht beeinflussen");
    }

    @Test
    void sanitizerFallbackHandlesEmptyInput() {
        assertEquals("NPC", PacketNpcRenderer.sanitizeProfileName(""));
        assertEquals("NPC", PacketNpcRenderer.sanitizeProfileName(null));
    }

    @Test
    void sanitizerReturnsNonNullForAnyInput() {
        // Edge case: ein String, der nach replace nichts enthält, fällt auf "NPC" zurück.
        // Hier nur sicherstellen, dass nichts crasht.
        assertNotNull(PacketNpcRenderer.sanitizeProfileName("a"));
    }

    private static NpcDefinition npc(String id, NpcSkin skin) {
        return new NpcDefinition(
                new NpcId(id),
                skin == null ? NpcSkin.ofName(id) : skin,
                new NpcLocation("world", 0, 64, 0, 0f, 0f),
                InteractionSettings.defaultClick(),
                Dialogue.empty(),
                NpcActions.empty()
        );
    }
}
