package hexcasino.machine;

import java.util.Objects;

public record SlotDifficulty(String id, String displayName, int costMultiplier, int frameMs, boolean enabled) {
    public SlotDifficulty {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        if (costMultiplier < 1) throw new IllegalArgumentException("costMultiplier must be >= 1");
        if (frameMs < 50) throw new IllegalArgumentException("frameMs must be >= 50");
    }
}
