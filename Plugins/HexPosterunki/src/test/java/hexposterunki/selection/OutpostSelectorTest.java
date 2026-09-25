package hexposterunki.selection;

import hexposterunki.config.BlockVec;
import hexposterunki.config.Cuboid;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.PointDef;
import hexposterunki.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutpostSelectorTest {

    private final OutpostSelector selector = new OutpostSelector();

    private static OutpostDefinition outpost(String id, int weight) {
        Cuboid region = Cuboid.of("world", new BlockVec(0, 0, 0), new BlockVec(10, 10, 10));
        PointDef center = new PointDef(5, 5, 5, 0, 0);
        return new OutpostDefinition(id, id, "world", region, center, center,
                Map.of("a", center), Map.of(), weight);
    }

    /** Returns the queued values, so a weighted pick can be steered exactly. */
    private static RandomSource scripted(int... ints) {
        Deque<Integer> queue = new ArrayDeque<>();
        for (int value : ints) {
            queue.add(value);
        }
        return new RandomSource() {
            @Override
            public double nextDouble() {
                return 0.0D;
            }

            @Override
            public int nextInt(int boundExclusive) {
                Integer next = queue.poll();
                return next == null ? 0 : Math.min(next, boundExclusive - 1);
            }
        };
    }

    @Test
    void emptyCatalogSelectsNothing() {
        assertTrue(selector.select(List.of(), null, true, scripted(0)).isEmpty());
    }

    @Test
    void zeroWeightOutpostsAreNeverSelected() {
        assertTrue(selector.select(List.of(outpost("a", 0)), null, true, scripted(0)).isEmpty());
    }

    @Test
    void theSameOutpostIsNotChosenTwiceInARow() {
        List<OutpostDefinition> catalog = List.of(outpost("a", 10), outpost("b", 10), outpost("c", 10));
        for (int roll = 0; roll < 20; roll++) {
            Optional<OutpostDefinition> picked = selector.select(catalog, "a", true, scripted(roll));
            assertNotEquals("a", picked.orElseThrow().id());
        }
    }

    @Test
    void aSingleOutpostRepeatsRatherThanStalling() {
        List<OutpostDefinition> catalog = List.of(outpost("a", 10));
        assertEquals("a", selector.select(catalog, "a", true, scripted(0)).orElseThrow().id());
    }

    @Test
    void repeatIsAllowedWhenTheRuleIsDisabled() {
        List<OutpostDefinition> catalog = List.of(outpost("a", 10), outpost("b", 10));
        // roll 0 -> first entry of the pool, which still contains "a"
        assertEquals("a", selector.select(catalog, "a", false, scripted(0)).orElseThrow().id());
    }

    @Test
    void weightsDecideTheProbability() {
        List<OutpostDefinition> catalog = List.of(outpost("rare", 1), outpost("common", 9));
        // total = 10; nextInt(10) + 1 = roll. roll 1 -> "rare", roll 2..10 -> "common".
        assertEquals("rare", selector.select(catalog, null, true, scripted(0)).orElseThrow().id());
        assertEquals("common", selector.select(catalog, null, true, scripted(1)).orElseThrow().id());
        assertEquals("common", selector.select(catalog, null, true, scripted(9)).orElseThrow().id());
    }

    @Test
    void seededSourceGivesReproducibleResults() {
        List<OutpostDefinition> catalog = List.of(outpost("a", 5), outpost("b", 5), outpost("c", 5));
        String first = selector.select(catalog, null, true, RandomSource.seeded(42L)).orElseThrow().id();
        String second = selector.select(catalog, null, true, RandomSource.seeded(42L)).orElseThrow().id();
        assertEquals(first, second);
    }
}
