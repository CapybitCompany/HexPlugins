package hexnpc.shop;

import hexnpc.shop.QuantityParser.Error;
import hexnpc.shop.QuantityParser.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walidacja własnej ilości: poprawne liczby, puste/nie-liczby, zero, liczby
 * ujemne, dziesiętne, przepełnienia i wartości powyżej maksimum.
 */
class QuantityParserTest {

    private static final int MAX = 100_000;

    @Test
    void acceptsValidPositiveInteger() {
        Result r = QuantityParser.parse("64", 1, MAX);
        assertTrue(r.ok());
        assertEquals(64, r.value());
    }

    @Test
    void acceptsValueAtMax() {
        Result r = QuantityParser.parse(String.valueOf(MAX), 1, MAX);
        assertTrue(r.ok());
        assertEquals(MAX, r.value());
    }

    @Test
    void rejectsEmptyAndBlank() {
        assertEquals(Error.EMPTY, QuantityParser.parse("", 1, MAX).error());
        assertEquals(Error.EMPTY, QuantityParser.parse("   ", 1, MAX).error());
        assertEquals(Error.EMPTY, QuantityParser.parse(null, 1, MAX).error());
    }

    @Test
    void rejectsZero() {
        Result r = QuantityParser.parse("0", 1, MAX);
        assertFalse(r.ok());
        assertEquals(Error.TOO_SMALL, r.error());
    }

    @Test
    void rejectsNegative() {
        // Minus nie jest cyfrą -> traktowane jako nie-liczba.
        assertEquals(Error.NOT_A_NUMBER, QuantityParser.parse("-5", 1, MAX).error());
    }

    @Test
    void rejectsDecimal() {
        assertEquals(Error.NOT_A_NUMBER, QuantityParser.parse("1.5", 1, MAX).error());
        assertEquals(Error.NOT_A_NUMBER, QuantityParser.parse("2,5", 1, MAX).error());
    }

    @Test
    void rejectsNonNumber() {
        assertEquals(Error.NOT_A_NUMBER, QuantityParser.parse("abc", 1, MAX).error());
        assertEquals(Error.NOT_A_NUMBER, QuantityParser.parse("10x", 1, MAX).error());
    }

    @Test
    void rejectsTooLarge() {
        assertEquals(Error.TOO_LARGE, QuantityParser.parse(String.valueOf((long) MAX + 1), 1, MAX).error());
    }

    @Test
    void rejectsOverflow() {
        // Za dużo cyfr, by zmieścić się w long — nie może wysadzić parsera.
        assertEquals(Error.TOO_LARGE, QuantityParser.parse("999999999999999999999999", 1, MAX).error());
    }

    @Test
    void trimsWhitespace() {
        Result r = QuantityParser.parse("  42  ", 1, MAX);
        assertTrue(r.ok());
        assertEquals(42, r.value());
    }
}
