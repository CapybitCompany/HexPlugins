package hexchat.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandNormalizationUtilTest {

    @Test
    void normalizesSlashesAndCase() {
        assertEquals("help", CommandNormalizationUtil.normalizeToSingleToken("/HELP"));
        assertEquals("help", CommandNormalizationUtil.normalizeToSingleToken("///help"));
        assertEquals("help", CommandNormalizationUtil.normalizeToSingleToken("  /Help  "));
    }

    @Test
    void takesOnlyFirstToken() {
        assertEquals("msg", CommandNormalizationUtil.normalizeToSingleToken("/msg gracz treść"));
    }

    @Test
    void blankInputYieldsEmptyToken() {
        assertEquals("", CommandNormalizationUtil.normalizeToSingleToken(null));
        assertEquals("", CommandNormalizationUtil.normalizeToSingleToken("   "));
        assertEquals("", CommandNormalizationUtil.normalizeToSingleToken("/"));
    }

    @Test
    void extractCandidatesIncludesNamespaceStrippedVariant() {
        Set<String> candidates = CommandNormalizationUtil.extractCandidates("/minecraft:help");

        assertTrue(candidates.contains("minecraft:help"), "Zawiera pełny token");
        assertTrue(candidates.contains("help"), "Zawiera wariant bez namespace");
    }

    @Test
    void extractCandidatesForPlainCommandHasSingleEntry() {
        Set<String> candidates = CommandNormalizationUtil.extractCandidates("spawn");

        assertEquals(Set.of("spawn"), candidates);
    }

    @Test
    void extractCandidatesForBlankIsEmpty() {
        assertTrue(CommandNormalizationUtil.extractCandidates("   ").isEmpty());
    }
}
