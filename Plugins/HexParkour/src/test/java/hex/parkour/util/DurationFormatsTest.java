package hex.parkour.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationFormatsTest {
    @Test
    void parsesConfiguredThresholds() {
        assertEquals(120_000L, DurationFormats.parseMillis("02:00.000"));
        assertEquals(90_500L, DurationFormats.parseMillis("1:30.500"));
        assertEquals(120_000L, DurationFormats.parseMillis("2m"));
        assertEquals(1_500L, DurationFormats.parseMillis("1.5s"));
        assertEquals(250L, DurationFormats.parseMillis("250ms"));
    }

    @Test
    void formatsMillisWithConfiguredPattern() {
        assertEquals("02:03.045", DurationFormats.formatMillis(123_045L, "mm:ss.SSS"));
    }

    @Test
    void invalidThresholdReturnsMinusOne() {
        assertEquals(-1L, DurationFormats.parseMillis(""));
        assertEquals(-1L, DurationFormats.parseMillis("abc"));
        assertEquals(-1L, DurationFormats.parseMillis("1:xx"));
    }
}
