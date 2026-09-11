package hex.minigames.runtime;

import hex.minigames.game.MinigameDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class GameSelectionService {
    private final Random random = new Random();

    public List<MinigameDefinition> select(List<MinigameDefinition> eligible, int count) {
        List<MinigameDefinition> pool = new ArrayList<>(eligible);
        List<MinigameDefinition> selected = new ArrayList<>();
        while (!pool.isEmpty() && selected.size() < count) {
            int totalWeight = pool.stream().mapToInt(MinigameDefinition::weight).sum();
            int roll = random.nextInt(Math.max(1, totalWeight));
            int cursor = 0;
            MinigameDefinition picked = pool.get(0);
            for (MinigameDefinition candidate : pool) {
                cursor += candidate.weight();
                if (roll < cursor) {
                    picked = candidate;
                    break;
                }
            }
            selected.add(picked);
            pool.remove(picked);
        }
        return selected;
    }
}
