package hexnpc.shop.sign;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wyodrębnianie „pierwszej istotnej linii" z tabliczki (pomija puste linie
 * i przycina białe znaki).
 */
class SignInputServiceTest {

    @Test
    void firstNonBlankReturnsFirstMeaningfulLine() {
        assertEquals("64", SignInputService.firstNonBlank(new String[]{"64", "", "", ""}));
        assertEquals("64", SignInputService.firstNonBlank(new String[]{"  64  ", "x", "", ""}));
        assertEquals("128", SignInputService.firstNonBlank(new String[]{"", "  ", "128", ""}));
    }

    @Test
    void firstNonBlankHandlesEmptyOrNull() {
        assertEquals("", SignInputService.firstNonBlank(null));
        assertEquals("", SignInputService.firstNonBlank(new String[]{"", "  ", ""}));
        assertEquals("", SignInputService.firstNonBlank(new String[]{}));
    }
}
