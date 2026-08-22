package hexcasino.machine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BusDriverEngineTest {

    private final BusDriverEngine engine = new BusDriverEngine();

    @Test
    void drawCardCanUseDeterministicRng() {
        Random first = new Random(12345L);
        Random second = new Random(12345L);
        for (int i = 0; i < 20; i++) {
            assertEquals(engine.drawCard(first), engine.drawCard(second));
        }
    }

    @Test
    void higherLowerEqualityIsLoss() {
        BusDriverEngine.Card sevenHearts = new BusDriverEngine.Card(7, BusDriverEngine.Suit.HEARTS);
        BusDriverEngine.Card sevenSpades = new BusDriverEngine.Card(7, BusDriverEngine.Suit.SPADES);
        assertFalse(engine.resolveHigherLower(sevenHearts, sevenSpades, true));
        assertFalse(engine.resolveHigherLower(sevenHearts, sevenSpades, false));
    }

    @Test
    void betweenOutsideBoundaryEqualityIsLoss() {
        BusDriverEngine.Card seven = new BusDriverEngine.Card(7, BusDriverEngine.Suit.HEARTS);
        BusDriverEngine.Card nine = new BusDriverEngine.Card(9, BusDriverEngine.Suit.CLUBS);
        BusDriverEngine.Card boundary = new BusDriverEngine.Card(7, BusDriverEngine.Suit.SPADES);
        BusDriverEngine.Card middle = new BusDriverEngine.Card(8, BusDriverEngine.Suit.DIAMONDS);
        BusDriverEngine.Card outside = new BusDriverEngine.Card(10, BusDriverEngine.Suit.DIAMONDS);

        assertFalse(engine.resolveBetweenOutside(seven, nine, boundary, false));
        assertFalse(engine.resolveBetweenOutside(seven, nine, boundary, true));
        assertTrue(engine.resolveBetweenOutside(seven, nine, middle, false));
        assertTrue(engine.resolveBetweenOutside(seven, nine, outside, true));
    }

    @Test
    void colorAndSuitRulesAreDeterministic() {
        BusDriverEngine.Card heart = new BusDriverEngine.Card(10, BusDriverEngine.Suit.HEARTS);
        assertTrue(engine.resolveColorGuess(heart, false));
        assertFalse(engine.resolveColorGuess(heart, true));
        assertTrue(engine.resolveSuitGuess(heart, BusDriverEngine.Suit.HEARTS));
        assertFalse(engine.resolveSuitGuess(heart, BusDriverEngine.Suit.SPADES));
    }

    @Test
    void fullFourStageLadderHasExpectedOptimalRtp() {
        assertEquals(0.8197314519799728D,
                engine.expectedOptimalRtp(List.of(1.4D, 2.2D, 2.7D, 11.0D)), 1.0e-12D);
    }

    @Test
    void shorterLaddersAreRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> engine.expectedOptimalRtp(List.of(1.6D, 2.0D)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> engine.expectedOptimalRtp(List.of(1.4D, 2.2D, 3.0D)));
    }

    @Test
    void x11FinalDecisionIsNotDominatedByCashout() {
        double cashout = 2.7D;
        double continueEv = 0.25D * 11.0D;
        assertTrue(continueEv > cashout);
    }
}
