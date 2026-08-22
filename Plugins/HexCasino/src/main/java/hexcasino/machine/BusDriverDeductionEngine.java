package hexcasino.machine;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Pure deterministic BusDriver deduction rules. Contains no RNG. */
public final class BusDriverDeductionEngine {
    public enum Suit {
        HEARTS("KIER"), DIAMONDS("KARO"), CLUBS("TREFL"), SPADES("PIK");
        private final String label;
        Suit(String label) { this.label = label; }
        public String label() { return label; }
    }

    public static final int MIN_RANK = 2;
    public static final int MAX_RANK = 14;

    public Validation validate(BusDriverBoard board) {
        Objects.requireNonNull(board, "board");
        List<String> errors = new ArrayList<>();
        for (BusDriverBoard.StageDefinition stage : board.stages()) {
            List<String> candidates = candidates(stage);
            if (candidates.size() != 1) {
                errors.add("board " + board.index() + " stage " + stage.id() + " has " + candidates.size() + " candidates: " + candidates);
            } else if (!candidates.getFirst().equals(stage.target())) {
                errors.add("board " + board.index() + " stage " + stage.id() + " resolves to " + candidates.getFirst() + " instead of " + stage.target());
            }
            for (BusDriverBoard.HintDefinition hint : stage.hints()) {
                if (!hintTrueForTarget(stage, hint)) {
                    errors.add("board " + board.index() + " stage " + stage.id() + " contains false hint " + hint);
                }
            }
        }
        return new Validation(errors.isEmpty(), List.copyOf(errors));
    }

    public List<String> candidates(BusDriverBoard.StageDefinition stage) {
        Objects.requireNonNull(stage, "stage");
        if (stage.type() == BusDriverBoard.StageType.SUIT_DEDUCTION) {
            Set<Suit> candidates = EnumSet.allOf(Suit.class);
            for (BusDriverBoard.HintDefinition hint : stage.hints()) {
                if (hint.type() != BusDriverBoard.HintType.NOT_SUIT) return List.of();
                try { candidates.remove(Suit.valueOf(hint.value())); }
                catch (IllegalArgumentException ex) { return List.of(); }
            }
            return candidates.stream().map(Enum::name).toList();
        }

        Set<Integer> candidates = new LinkedHashSet<>();
        for (int rank = MIN_RANK; rank <= MAX_RANK; rank++) candidates.add(rank);
        for (BusDriverBoard.HintDefinition hint : stage.hints()) {
            int boundary;
            try { boundary = Integer.parseInt(hint.value()); }
            catch (NumberFormatException ex) { return List.of(); }
            switch (hint.type()) {
                case GREATER_THAN -> candidates.removeIf(rank -> rank <= boundary);
                case LESS_THAN -> candidates.removeIf(rank -> rank >= boundary);
                default -> { return List.of(); }
            }
        }
        return candidates.stream().map(String::valueOf).toList();
    }

    public boolean answerCorrect(BusDriverBoard.StageDefinition stage, String answer) {
        if (answer == null) return false;
        return stage.target().equals(answer.trim().toUpperCase(Locale.ROOT));
    }

    public boolean hintTrueForTarget(BusDriverBoard.StageDefinition stage, BusDriverBoard.HintDefinition hint) {
        try {
            if (stage.type() == BusDriverBoard.StageType.SUIT_DEDUCTION) {
                return hint.type() == BusDriverBoard.HintType.NOT_SUIT && !stage.target().equals(hint.value());
            }
            int target = Integer.parseInt(stage.target());
            int boundary = Integer.parseInt(hint.value());
            return switch (hint.type()) {
                case GREATER_THAN -> target > boundary;
                case LESS_THAN -> target < boundary;
                default -> false;
            };
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public String hintText(BusDriverBoard.HintDefinition hint) {
        return switch (hint.type()) {
            case NOT_SUIT -> "To nie jest " + suitLabel(hint.value()) + ".";
            case GREATER_THAN -> "Szukana karta jest WIĘKSZA niż " + rankLabel(Integer.parseInt(hint.value())) + ".";
            case LESS_THAN -> "Szukana karta jest MNIEJSZA niż " + rankLabel(Integer.parseInt(hint.value())) + ".";
        };
    }

    public static String suitLabel(String raw) {
        try { return Suit.valueOf(raw).label(); }
        catch (RuntimeException ex) { return raw; }
    }

    public static String rankLabel(int rank) {
        return switch (rank) {
            case 11 -> "WALET";
            case 12 -> "DAMA";
            case 13 -> "KRÓL";
            case 14 -> "AS";
            default -> Integer.toString(rank);
        };
    }

    public record Validation(boolean valid, List<String> errors) {}
}
