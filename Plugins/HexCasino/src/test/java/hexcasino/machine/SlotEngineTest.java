package hexcasino.machine;

import hexcasino.config.CasinoConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SlotEngineTest {
    private static final List<Integer> MAX_GRID = List.of(
            2, 3, 4, 5, 6,
            11, 12, 13, 14, 15,
            20, 21, 22, 23, 24);
    private static final Map<String, Double> REWARDS = Map.of(
            "flint", 1.00D, "melon_slice", 1.50D, "gold_nugget", 2.00D, "blaze_powder", 3.00D,
            "amethyst_shard", 4.00D, "emerald", 6.00D, "diamond", 10.00D, "nether_star", 16.00D);
    private final SlotEngine engine = new SlotEngine();

    @Test void geometryContainsExactlyOneEightAndTwentyTwoPatterns() {
        assertEquals(1, layout(1).winningPatterns().size());
        assertEquals(8, layout(3).winningPatterns().size());
        assertEquals(22, layout(5).winningPatterns().size());
    }

    @Test void oneLineIsHorizontalAndHasThreeIndependentStops() {
        SlotLayout layout = layout(1);
        assertEquals(List.of(12,13,14), layout.inventorySlots());
        assertEquals(3, layout.stopUnitCount());
    }

    @Test void reelWindowMovesTopToMiddleToBottom() {
        DeterministicReelSet set = sequentialSet();
        SlotLayout layout = layout(3);
        String[] frame0 = engine.visibleSymbols(set, layout, new int[]{2,2,2});
        String[] frame1 = engine.visibleSymbols(set, layout, new int[]{3,3,3});
        // old TOP at reel 0 becomes new MIDDLE at reel 0
        assertEquals(frame0[0], frame1[3]);
        // old MIDDLE becomes new BOTTOM
        assertEquals(frame0[3], frame1[6]);
    }

    @Test void sameReelSetAndStopsAlwaysProduceSameOutcome() {
        DeterministicReelSet set = sequentialSet();
        SlotLayout layout = layout(5);
        int[] stops = {5, 13, 21, 34, 55};
        assertArrayEquals(engine.outcomeFromStoppedPositions(set, layout, stops).symbols(),
                engine.outcomeFromStoppedPositions(set, layout, stops).symbols());
    }

    @Test void oneLineUsesThreePhysicalReels() {
        DeterministicReelSet set = sequentialSet();
        SlotOutcome outcome = engine.outcomeFromStoppedPositions(set, layout(1), new int[]{0,1,2});
        assertEquals(set.reel(0).symbolAt(0), outcome.symbol(0));
        assertEquals(set.reel(1).symbolAt(1), outcome.symbol(1));
        assertEquals(set.reel(2).symbolAt(2), outcome.symbol(2));
    }

    @Test void verticalTripleAlwaysWins() {
        SlotLayout layout = layout(3);
        SlotOutcome outcome = outcome(3,
                "blaze_powder","u1","u2",
                "blaze_powder","u3","u4",
                "blaze_powder","u5","u6");
        SlotSpinResult result = engine.evaluate(outcome, layout, config(), 60.0D, REWARDS);
        assertEquals(1, result.winningPatternCount());
        assertEquals(60.0D / 8.0D * 3.00D, result.win(), 1e-9);
    }

    @Test void longDiagonalsAreCountedInFiveByThree() {
        SlotLayout layout = layout(5);
        SlotOutcome outcome = outcome(5,
                "diamond","u1","u2","u3","diamond",
                "u4","u5","diamond","u6","u7",
                "diamond","u8","u9","u10","diamond");
        SlotSpinResult result = engine.evaluate(outcome, layout, config(), 20.0D, REWARDS);
        long longDiagonals = result.hits().stream().filter(hit -> {
            List<GridPoint> p = hit.pattern().points();
            return Math.abs(p.get(1).x() - p.get(0).x()) == 2;
        }).count();
        assertEquals(2, longDiagonals);
    }

    @Test void horizontalOneThreeFiveWithGapsIsNotAWin() {
        SlotSpinResult result = engine.evaluate(outcome(5,
                "flint","u1","flint","u2","flint",
                "u3","u4","u5","u6","u7",
                "u8","u9","u10","u11","u12"), layout(5), config(), 20.0D, REWARDS);
        assertEquals(0, result.winningPatternCount());
    }

    @Test void overlappingPatternsSumWithoutHardCap() {
        String[] symbols = new String[15];
        Arrays.fill(symbols, "nether_star");
        SlotSpinResult result = engine.evaluate(new SlotOutcome(5,3,symbols), layout(5), config(), 60.0D, REWARDS);
        assertEquals(22, result.winningPatternCount());
        assertEquals(60.0D * 16.0D, result.win(), 1e-9);
    }

    private SlotLayout layout(int reels) { return SlotLayout.centered(reels,3,MAX_GRID); }
    private SlotOutcome outcome(int reels, String... symbols) { return new SlotOutcome(reels,3,symbols); }

    private DeterministicReelSet sequentialSet() {
        List<String> base = List.of("flint","melon_slice","gold_nugget","blaze_powder","amethyst_shard","emerald","diamond","nether_star");
        String[] strip = new String[86];
        for (int i=0;i<strip.length;i++) strip[i]=base.get(i%base.size());
        List<ReelStrip> reels = java.util.stream.IntStream.range(0,5)
                .mapToObj(i -> new ReelStrip("r"+i, List.of(strip))).toList();
        return new DeterministicReelSet(1,reels,new int[]{0,1,2,3,4});
    }

    private CasinoConfig config() {
        List<CasinoConfig.Symbol> symbols = REWARDS.keySet().stream().map(id ->
                new CasinoConfig.Symbol(id, null, id, id, List.of(), REWARDS.get(id), 1.0D, "", List.of(), null)).toList();
        Map<String,CasinoConfig.Symbol> byId = new LinkedHashMap<>(); symbols.forEach(s -> byId.put(s.id(),s));
        return new CasinoConfig(Map.of(),null,null,null,null,null,null,null,List.of(),List.of(),symbols,Map.copyOf(byId),null,null);
    }
}
