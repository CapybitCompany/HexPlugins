package hexcasino.machine;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic regression checks; there is intentionally no Monte Carlo/RNG in Skill Reel tests. */
class SlotEngineMonteCarloTest {
    @Test void geometryIsStableAcrossRepeatedGeneration() {
        for (int reels : new int[]{1,3,5}) {
            List<WinningPattern> first = SlotLayout.generateWinningPatterns(reels,3);
            for (int i=0;i<1000;i++) assertEquals(first, SlotLayout.generateWinningPatterns(reels,3));
            Set<String> ids = new HashSet<>(); first.forEach(pattern -> ids.add(pattern.id()));
            assertEquals(first.size(), ids.size());
        }
        assertEquals(1, SlotLayout.generateWinningPatterns(1,3).size());
        assertEquals(8, SlotLayout.generateWinningPatterns(3,3).size());
        assertEquals(22, SlotLayout.generateWinningPatterns(5,3).size());
    }

    @Test void noGapVectorIsGenerated() {
        assertTrue(SlotLayout.generateWinningPatterns(5,3).stream().noneMatch(pattern -> {
            GridPoint a=pattern.points().get(0), b=pattern.points().get(1);
            return Math.abs(b.x()-a.x())==2 && b.y()==a.y();
        }));
    }
}
