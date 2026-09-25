package hexposterunki.boss;

import hexposterunki.config.BossConfig;
import hexposterunki.util.RandomSource;
import hexposterunki.util.Weighted;

import java.util.Optional;

/**
 * Rolls the boss of a run exactly once: first the global spawn chance, then the weighted boss
 * list. Rare/strong bosses carry low weights, common/weaker ones high weights.
 */
public final class BossRoller {

    /** @return the boss id for this run, or empty when the run has no boss. */
    public Optional<String> roll(BossConfig config, RandomSource random) {
        if (config == null || !config.usable()) {
            return Optional.empty();
        }
        if (random.nextDouble() >= config.spawnChance()) {
            return Optional.empty();
        }
        return Weighted.pick(config.bosses(), BossConfig.WeightedBoss::weight, random)
                .map(BossConfig.WeightedBoss::bossId);
    }
}
