package hexnpc.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Winkelberechnung fuer Look-At (Minecraft-Konvention: Yaw 0 -> +Z, im Uhrzeigersinn;
 * positiver Pitch -> nach unten).
 */
class LookAtCalculatorTest {

    private static final double EYE = 1.62D;

    @Test
    void facingPositiveZIsYawZero() {
        float[] yp = LookAtCalculator.yawPitch(0, EYE, 0, 0, EYE, 5);
        assertEquals(0.0f, yp[0], 1e-3, "+Z -> yaw 0");
        assertEquals(0.0f, yp[1], 1e-3, "gleiche Hoehe -> pitch 0");
    }

    @Test
    void facingNegativeXIsYawNinety() {
        float[] yp = LookAtCalculator.yawPitch(0, EYE, 0, -5, EYE, 0);
        assertEquals(90.0f, yp[0], 1e-3, "-X -> yaw +90");
    }

    @Test
    void facingPositiveXIsYawMinusNinety() {
        float[] yp = LookAtCalculator.yawPitch(0, EYE, 0, 5, EYE, 0);
        assertEquals(-90.0f, yp[0], 1e-3, "+X -> yaw -90");
    }

    @Test
    void facingNegativeZIsYawOneEighty() {
        float[] yp = LookAtCalculator.yawPitch(0, EYE, 0, 0, EYE, -5);
        assertEquals(180.0f, Math.abs(yp[0]), 1e-3, "-Z -> yaw ±180");
    }

    @Test
    void targetAboveGivesNegativePitch() {
        // Ziel hoeher als NPC-Augen -> Blick nach oben -> negativer Pitch.
        float[] yp = LookAtCalculator.yawPitch(0, EYE, 0, 0, EYE + 5, 5);
        assertTrue(yp[1] < 0.0f, "Ziel oben -> pitch < 0, war " + yp[1]);
    }

    @Test
    void targetBelowGivesPositivePitch() {
        float[] yp = LookAtCalculator.yawPitch(0, EYE + 5, 0, 0, EYE, 5);
        assertTrue(yp[1] > 0.0f, "Ziel unten -> pitch > 0, war " + yp[1]);
    }

    @Test
    void fortyFiveDegreeDownPitch() {
        // Horizontaldistanz == Hoehendifferenz -> 45° nach unten.
        float[] yp = LookAtCalculator.yawPitch(0, 10, 0, 0, 5, 5);
        assertEquals(45.0f, yp[1], 1e-3);
    }

    @Test
    void coincidentPointsReturnZero() {
        float[] yp = LookAtCalculator.yawPitch(1, 2, 3, 1, 2, 3);
        assertEquals(0.0f, yp[0], 1e-6);
        assertEquals(0.0f, yp[1], 1e-6);
    }

    @Test
    void pitchIsClampedWithinRange() {
        float[] yp = LookAtCalculator.yawPitch(0, 100, 0, 0.0001, 0, 0.0001);
        assertTrue(yp[1] <= 90.0f && yp[1] >= -90.0f, "pitch within [-90,90], war " + yp[1]);
    }
}
