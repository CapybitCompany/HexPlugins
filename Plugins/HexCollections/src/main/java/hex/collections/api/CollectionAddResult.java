package hex.collections.api;

import java.util.List;

public record CollectionAddResult(boolean accepted, String reason, long oldAmount, long newAmount, int oldLevel, int newLevel, List<Integer> unlockedLevels) {
    public static CollectionAddResult denied(String reason) {
        return new CollectionAddResult(false, reason, 0L, 0L, 0, 0, List.of());
    }

    public boolean levelUp() {
        return newLevel > oldLevel;
    }
}

