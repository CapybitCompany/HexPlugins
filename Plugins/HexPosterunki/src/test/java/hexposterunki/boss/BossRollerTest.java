package hexposterunki.boss;

import hexposterunki.config.BossConfig;
import hexposterunki.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossRollerTest {

    private final BossRoller roller = new BossRoller();

    private static BossConfig config(double chance, BossConfig.WeightedBoss... bosses) {
        return new BossConfig(true, chance, "stormbossy", "1.0", true, true, List.of(bosses));
    }

    /** Injected randomness: first double gates the spawn chance, then ints drive the weights. */
    private static RandomSource scripted(double firstDouble, int... ints) {
        Deque<Integer> queue = new ArrayDeque<>();
        for (int value : ints) {
            queue.add(value);
        }
        return new RandomSource() {
            @Override
            public double nextDouble() {
                return firstDouble;
            }

            @Override
            public int nextInt(int boundExclusive) {
                Integer next = queue.poll();
                return next == null ? 0 : Math.min(next, boundExclusive - 1);
            }
        };
    }

    @Test
    void disabledBossPhaseNeverRolls() {
        BossConfig disabled = new BossConfig(false, 1.0D, "stormbossy", "1.0", true, true,
                List.of(new BossConfig.WeightedBoss("a", 1)));
        assertTrue(roller.roll(disabled, scripted(0.0D, 0)).isEmpty());
    }

    @Test
    void emptyBossListNeverRolls() {
        assertTrue(roller.roll(config(1.0D), scripted(0.0D, 0)).isEmpty());
    }

    @Test
    void spawnChanceGatesTheRoll() {
        BossConfig cfg = config(0.35D, new BossConfig.WeightedBoss("golem", 1));
        assertTrue(roller.roll(cfg, scripted(0.34D, 0)).isPresent(), "0.34 < 0.35 -> boss");
        assertTrue(roller.roll(cfg, scripted(0.35D, 0)).isEmpty(), "0.35 nie jest < 0.35 -> brak bossa");
        assertTrue(roller.roll(cfg, scripted(0.99D, 0)).isEmpty());
    }

    @Test
    void rareBossesHaveLowWeightsAndCommonOnesHigh() {
        BossConfig cfg = config(1.0D,
                new BossConfig.WeightedBoss("pradawny_tytan", 2),
                new BossConfig.WeightedBoss("lesny_golem", 98));
        // total = 100; roll = nextInt(100) + 1. roll 1..2 -> tytan, roll 3..100 -> golem.
        assertEquals("pradawny_tytan", roller.roll(cfg, scripted(0.0D, 0)).orElseThrow());
        assertEquals("pradawny_tytan", roller.roll(cfg, scripted(0.0D, 1)).orElseThrow());
        assertEquals("lesny_golem", roller.roll(cfg, scripted(0.0D, 2)).orElseThrow());
        assertEquals("lesny_golem", roller.roll(cfg, scripted(0.0D, 99)).orElseThrow());
    }

    @Test
    void weightDistributionFollowsTheConfiguredRatioOverManyRolls() {
        BossConfig cfg = config(1.0D,
                new BossConfig.WeightedBoss("rzadki", 1),
                new BossConfig.WeightedBoss("czesty", 99));
        RandomSource random = RandomSource.seeded(1234L);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            Optional<String> rolled = roller.roll(cfg, random);
            counts.merge(rolled.orElse("-"), 1, Integer::sum);
        }
        int rare = counts.getOrDefault("rzadki", 0);
        int common = counts.getOrDefault("czesty", 0);
        assertEquals(10_000, rare + common);
        assertTrue(common > rare * 20, "boss o wysokiej wadze musi wypadać znacznie częściej");
    }

    @Test
    void zeroWeightBossesAreIgnored() {
        BossConfig cfg = config(1.0D,
                new BossConfig.WeightedBoss("wylaczony", 0),
                new BossConfig.WeightedBoss("aktywny", 5));
        for (int roll = 0; roll < 5; roll++) {
            assertEquals("aktywny", roller.roll(cfg, scripted(0.0D, roll)).orElseThrow());
        }
    }
}
