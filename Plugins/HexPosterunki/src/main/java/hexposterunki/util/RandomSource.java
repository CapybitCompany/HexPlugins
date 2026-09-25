package hexposterunki.util;

/**
 * Injectable randomness so every weighted decision (outpost pick, boss roll, loot roll)
 * stays deterministic under test.
 */
public interface RandomSource {

    /** @return a value in {@code [0.0, 1.0)}. */
    double nextDouble();

    /** @return a value in {@code [0, boundExclusive)}; {@code boundExclusive} must be positive. */
    int nextInt(int boundExclusive);

    static RandomSource threadLocal() {
        return new RandomSource() {
            @Override
            public double nextDouble() {
                return java.util.concurrent.ThreadLocalRandom.current().nextDouble();
            }

            @Override
            public int nextInt(int boundExclusive) {
                return java.util.concurrent.ThreadLocalRandom.current().nextInt(boundExclusive);
            }
        };
    }

    /** Deterministic source for tests and reproducible debugging. */
    static RandomSource seeded(long seed) {
        java.util.Random random = new java.util.Random(seed);
        return new RandomSource() {
            @Override
            public double nextDouble() {
                return random.nextDouble();
            }

            @Override
            public int nextInt(int boundExclusive) {
                return random.nextInt(boundExclusive);
            }
        };
    }
}
