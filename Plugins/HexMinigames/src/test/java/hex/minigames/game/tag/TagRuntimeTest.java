package hex.minigames.game.tag;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class TagRuntimeTest {
    @Test
    void correctNumberOfTaggersForEverySupportedPlayerCount() {
        for (int count = 2; count <= 14; count++) {
            var runtime = runtime(count);
            assertEquals(count <= 4 ? 1 : count <= 8 ? 2 : 3, runtime.taggers().size());
        }
        assertEquals(0, TagRuntime.taggerCount(1));
    }

    @Test
    void transfersProtectTheOldTaggerForExactlyFortyTicks() {
        List<UUID> players = players(4);
        var runtime = new TagRuntime(players, new Random(1), 40, 0, 3000);
        UUID old = runtime.taggers().iterator().next();
        UUID next = players.stream().filter(id -> !runtime.isTagger(id)).findFirst().orElseThrow();
        assertTrue(runtime.transfer(old, next, 100));
        assertFalse(runtime.isTagger(old));
        assertTrue(runtime.isTagger(next));
        assertFalse(runtime.transfer(next, old, 100));
        assertFalse(runtime.transfer(next, old, 139));
        assertTrue(runtime.transfer(next, old, 140));
        assertFalse(runtime.transfer(next, old, 141));
        assertFalse(runtime.transfer(old, UUID.randomUUID(), 200));
        assertEquals(1, runtime.taggers().size());
    }

    @Test
    void runnerTimeAccumulatesAcrossRolesAndNeverExceedsRoundEnd() {
        List<UUID> players = players(4);
        var runtime = new TagRuntime(players, new Random(1), 40, 0, 3000);
        UUID tagger = runtime.taggers().iterator().next();
        UUID runner = players.stream().filter(id -> !runtime.isTagger(id)).findFirst().orElseThrow();
        assertTrue(runtime.transfer(tagger, runner, 600));
        assertEquals(600, runtime.runnerTicks(runner, 800));
        assertEquals(1, runtime.points(runner, 800, List.of(30, 75, 120)));
        assertTrue(runtime.transfer(runner, tagger, 1000));
        assertEquals(2000, runtime.runnerTicks(runner, 2400));
        assertEquals(2, runtime.points(runner, 2400, List.of(30, 75, 120)));
        assertEquals(2600, runtime.runnerTicks(runner, 9999));
        assertEquals(3, runtime.points(runner, 9999, List.of(30, 75, 120)));
        assertFalse(runtime.transfer(tagger, runner, 3000));
    }

    @Test
    void leavingTaggerIsReplacedAndPopulationThresholdsAreRespected() {
        var runtime = runtime(9);
        UUID leaving = runtime.taggers().iterator().next();
        runtime.remove(leaving, 300);
        assertEquals(2, runtime.taggers().size());
        runtime = runtime(4);
        leaving = runtime.taggers().iterator().next();
        assertEquals(1, runtime.remove(leaving, 300).size());
        assertEquals(1, runtime.taggers().size());
        assertFalse(runtime.isTagger(leaving));
    }

    private TagRuntime runtime(int count) { return new TagRuntime(players(count), new Random(1), 40, 0, 3000); }
    private List<UUID> players(int count) { return java.util.stream.IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList(); }
}
