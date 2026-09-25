package hexposterunki.util;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToIntFunction;

/** Shared weighted-pick helper used by the outpost selector and the boss roller. */
public final class Weighted {

    private Weighted() {
    }

    /**
     * Picks one element proportionally to its weight. Elements with a weight of zero or less
     * are ignored. Returns empty when no candidate has a usable weight.
     */
    public static <T> Optional<T> pick(List<T> candidates, ToIntFunction<T> weight, RandomSource random) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(weight, "weight");
        Objects.requireNonNull(random, "random");

        long total = 0L;
        for (T candidate : candidates) {
            int w = weight.applyAsInt(candidate);
            if (w > 0) {
                total += w;
            }
        }
        if (total <= 0L) {
            return Optional.empty();
        }

        // nextInt is bounded to int, weights are small integers, so the cast is safe here.
        int roll = random.nextInt((int) Math.min(total, Integer.MAX_VALUE)) + 1;
        int cursor = 0;
        for (T candidate : candidates) {
            int w = weight.applyAsInt(candidate);
            if (w <= 0) {
                continue;
            }
            cursor += w;
            if (roll <= cursor) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
