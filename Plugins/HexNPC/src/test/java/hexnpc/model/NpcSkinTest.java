package hexnpc.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Absicherung von Skin-Quellen und insbesondere Review-Finding 1: {@link NpcSkin#hasTexture()}
 * verlangt value UND signature — halbe Textures duerfen nicht als gueltig gelten.
 */
class NpcSkinTest {

    @Test
    void hasTextureRequiresBothValueAndSignature() {
        assertTrue(NpcSkin.ofTexture("VAL", "SIG").hasTexture(), "value + signature -> gueltig");
        assertFalse(new NpcSkin(null, "VAL", null).hasTexture(), "nur value -> ungueltig");
        assertFalse(new NpcSkin(null, null, "SIG").hasTexture(), "nur signature -> ungueltig");
        assertFalse(NpcSkin.ofName("Notch").hasTexture(), "nur name -> ungueltig");
    }

    @Test
    void sourceHelpersSetTheRightField() {
        assertTrue(NpcSkin.ofUrl("https://x/skin.png").hasUrl());
        assertTrue(NpcSkin.ofMineSkinUuid("abc").hasMineSkinUuid());
        assertTrue(NpcSkin.ofName("Notch").hasName());
    }

    @Test
    void withTextureKeepsSourceButAddsTextures() {
        NpcSkin urlSkin = NpcSkin.ofUrl("https://x/skin.png");
        NpcSkin resolved = urlSkin.withTexture("V", "S");
        assertTrue(resolved.hasTexture());
        assertEquals("https://x/skin.png", resolved.url(), "Quelle bleibt erhalten");
        assertEquals("V", resolved.value());
        assertEquals("S", resolved.signature());
    }

    @Test
    void blankFieldsAreNormalizedToNull() {
        NpcSkin s = new NpcSkin("  ", "  ", "  ", "  ", "  ");
        assertFalse(s.hasName());
        assertFalse(s.hasTexture());
        assertFalse(s.hasUrl());
        assertFalse(s.hasMineSkinUuid());
    }
}
