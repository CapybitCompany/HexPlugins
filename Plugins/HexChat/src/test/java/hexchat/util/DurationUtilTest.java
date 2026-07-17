package hexchat.util;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationUtilTest {

    @Test
    void parsesSingleUnits() {
        assertEquals(Optional.of(30_000L), DurationUtil.parseMillis("30s"));
        assertEquals(Optional.of(1_800_000L), DurationUtil.parseMillis("30m"));
        assertEquals(Optional.of(7_200_000L), DurationUtil.parseMillis("2h"));
        assertEquals(Optional.of(86_400_000L), DurationUtil.parseMillis("1d"));
        assertEquals(Optional.of(604_800_000L), DurationUtil.parseMillis("1w"));
    }

    @Test
    void parsesCombinedUnits() {
        assertEquals(Optional.of(9_000_000L), DurationUtil.parseMillis("2h30m"));
        assertEquals(Optional.of(90_061_000L), DurationUtil.parseMillis("1d1h1m1s"));
    }

    @Test
    void parsesPermanentForms() {
        assertEquals(Optional.of(DurationUtil.PERMANENT), DurationUtil.parseMillis("perm"));
        assertEquals(Optional.of(DurationUtil.PERMANENT), DurationUtil.parseMillis("permanent"));
        assertEquals(Optional.of(DurationUtil.PERMANENT), DurationUtil.parseMillis("0"));
        assertEquals(Optional.of(DurationUtil.PERMANENT), DurationUtil.parseMillis(""));
    }

    @Test
    void isCaseInsensitiveAndTrims() {
        assertEquals(Optional.of(3_600_000L), DurationUtil.parseMillis("  1H "));
    }

    @Test
    void rejectsInvalidInput() {
        assertTrue(DurationUtil.parseMillis("10x").isEmpty());
        assertTrue(DurationUtil.parseMillis("abc").isEmpty());
        assertTrue(DurationUtil.parseMillis("5m3").isEmpty());
        assertTrue(DurationUtil.parseMillis(null).isEmpty());
    }

    @Test
    void rejectsHugeNumbersWithoutThrowing() {
        // Zbyt duża liczba dla long (NumberFormatException wewnątrz) -> Optional.empty(), bez wyjątku.
        assertTrue(DurationUtil.parseMillis("999999999999999999999999999999999999d").isEmpty());
        // Overflow przy mnożeniu jednostki (mieści się w long, ale *86_400_000 przepełnia) -> empty.
        assertTrue(DurationUtil.parseMillis("9223372036854775807d").isEmpty());
        // Overflow przy sumowaniu wielu tokenów -> empty.
        assertTrue(DurationUtil.parseMillis("9000000000000w9000000000000w").isEmpty());
    }

    @Test
    void formatsRemainingReadable() {
        assertEquals("0s", DurationUtil.formatRemaining(0L));
        assertEquals("30s", DurationUtil.formatRemaining(30_000L));
        assertEquals("2h 30m", DurationUtil.formatRemaining(9_000_000L));
        assertEquals("1d", DurationUtil.formatRemaining(86_400_000L));
    }
}
