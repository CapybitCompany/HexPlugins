package hexnpc.render.packet;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der technische Profilname ist zugleich UserProfile-Username und Scoreboard-Team-Entry.
 * Er muss ein gueltiger Mojang-Username sein (≤ 16 Zeichen, nur {@code [A-Za-z0-9_]}),
 * stabil fuer denselben Input und aus Zufallsbits abgeleitet (nicht aus NpcId/Skin/Nickname),
 * damit die Kollisionswahrscheinlichkeit mit echten Spielern/anderen NPCs verschwindend ist.
 */
class PacketNpcRendererProfileNameTest {

    private static final Pattern VALID_USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    @Test
    void profileNameIsValidMojangUsername() {
        String name = PacketNpcRenderer.buildTechnicalProfileName(UUID.randomUUID(), 0);
        assertTrue(VALID_USERNAME.matcher(name).matches(),
                "must be <=16 chars and only [A-Za-z0-9_], got '" + name + "'");
    }

    @Test
    void profileNameNeverExceedsSixteenChars() {
        for (int i = 0; i < 1000; i++) {
            String name = PacketNpcRenderer.buildTechnicalProfileName(UUID.randomUUID(), i);
            assertTrue(name.length() <= 16, "profile name must be <= 16 chars, got " + name);
            assertTrue(VALID_USERNAME.matcher(name).matches(), "valid username chars only: " + name);
        }
    }

    @Test
    void sameUuidAndSaltIsStable() {
        UUID uuid = UUID.randomUUID();
        assertEquals(
                PacketNpcRenderer.buildTechnicalProfileName(uuid, 3),
                PacketNpcRenderer.buildTechnicalProfileName(uuid, 3),
                "same uuid+salt must map to the same technical name");
    }

    @Test
    void differentSaltProducesDifferentName() {
        UUID uuid = UUID.randomUUID();
        assertNotEquals(
                PacketNpcRenderer.buildTechnicalProfileName(uuid, 0),
                PacketNpcRenderer.buildTechnicalProfileName(uuid, 1),
                "the collision-avoidance salt must actually change the name");
    }

    @Test
    void namesAreEffectivelyUniqueAcrossManyUuids() {
        Set<String> names = new HashSet<>();
        int collisions = 0;
        for (int i = 0; i < 5000; i++) {
            if (!names.add(PacketNpcRenderer.buildTechnicalProfileName(UUID.randomUUID(), 0))) {
                collisions++;
            }
        }
        assertTrue(collisions == 0, "expected no collisions across random uuids, got " + collisions);
    }

    @Test
    void carriesNpcPrefix() {
        assertTrue(PacketNpcRenderer.buildTechnicalProfileName(UUID.randomUUID(), 0).startsWith("npc_"),
                "technical entry must be recognizable as an NPC");
    }
}
