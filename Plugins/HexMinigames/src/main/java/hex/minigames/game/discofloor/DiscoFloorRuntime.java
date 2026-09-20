package hex.minigames.game.discofloor;

import java.util.*;

/** Seven rounds: three seconds before reveal, three to react, five with missing tiles. */
public final class DiscoFloorRuntime {
    public static final int ROUNDS = 7;
    public static final int ROUND_TICKS = 220;
    private final int[] colors = new int[ROUNDS];
    private final Map<UUID, Integer> survived = new HashMap<>();
    private final Set<UUID> eliminated = new HashSet<>();
    public DiscoFloorRuntime(int colorCount, Random random) {
        if (colorCount < 1) throw new IllegalArgumentException("Disco Floor needs at least one color");
        for (int i = 0; i < ROUNDS; i++) {
            int color = random.nextInt(colorCount);
            if (i > 0 && colorCount > 1 && color == colors[i - 1]) color = (color + 1 + random.nextInt(colorCount - 1)) % colorCount;
            colors[i] = color;
        }
    }
    public int color(int round) { return colors[round]; }
    public void survive(UUID id, int completedRounds) {
        if (!eliminated.contains(id)) survived.merge(id, Math.min(ROUNDS, completedRounds), Math::max);
    }
    public boolean eliminate(UUID id) { return eliminated.add(id); }
    public int survived(UUID id) { return survived.getOrDefault(id, 0); }
    public int points(UUID id) {
        int rounds = survived(id);
        return rounds >= 7 ? 4 : rounds >= 5 ? 3 : rounds >= 3 ? 2 : rounds >= 2 ? 1 : 0;
    }
}
