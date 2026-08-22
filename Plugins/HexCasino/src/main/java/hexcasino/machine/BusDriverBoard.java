package hexcasino.machine;

import java.util.List;
import java.util.Objects;

/** Immutable definition of one frozen deterministic BusDriver board. */
public record BusDriverBoard(int index, int version, List<StageDefinition> stages) {
    public BusDriverBoard {
        if (index < 1) throw new IllegalArgumentException("board index must be positive");
        stages = List.copyOf(stages);
        if (stages.isEmpty()) throw new IllegalArgumentException("board must contain stages");
    }

    public StageDefinition stage(int zeroBasedIndex) {
        return stages.get(zeroBasedIndex);
    }

    public enum StageType {
        SUIT_DEDUCTION,
        RANK_DEDUCTION
    }

    public enum HintType {
        NOT_SUIT,
        GREATER_THAN,
        LESS_THAN
    }

    public record HintDefinition(int slot, HintType type, String value) {
        public HintDefinition {
            if (slot < 0) throw new IllegalArgumentException("hint slot must be non-negative");
            Objects.requireNonNull(type, "type");
            value = Objects.requireNonNull(value, "value").trim().toUpperCase(java.util.Locale.ROOT);
        }
    }

    public record StageDefinition(int id, StageType type, String target, List<HintDefinition> hints) {
        public StageDefinition {
            if (id < 1) throw new IllegalArgumentException("stage id must be positive");
            Objects.requireNonNull(type, "type");
            target = Objects.requireNonNull(target, "target").trim().toUpperCase(java.util.Locale.ROOT);
            hints = List.copyOf(hints);
            if (hints.isEmpty()) throw new IllegalArgumentException("stage must contain hints");
        }
    }
}
