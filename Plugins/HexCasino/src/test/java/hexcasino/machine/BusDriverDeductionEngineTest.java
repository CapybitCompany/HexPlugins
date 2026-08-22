package hexcasino.machine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class BusDriverDeductionEngineTest {
    private final BusDriverDeductionEngine engine = new BusDriverDeductionEngine();

    @Test void suitHintsResolveExactlyOneSuit() {
        var stage = new BusDriverBoard.StageDefinition(1, BusDriverBoard.StageType.SUIT_DEDUCTION, "HEARTS", List.of(
                new BusDriverBoard.HintDefinition(11, BusDriverBoard.HintType.NOT_SUIT, "DIAMONDS"),
                new BusDriverBoard.HintDefinition(13, BusDriverBoard.HintType.NOT_SUIT, "CLUBS"),
                new BusDriverBoard.HintDefinition(15, BusDriverBoard.HintType.NOT_SUIT, "SPADES")
        ));
        assertEquals(List.of("HEARTS"), engine.candidates(stage));
    }

    @Test void rankHintsResolveExactlyOneRank() {
        var stage = new BusDriverBoard.StageDefinition(2, BusDriverBoard.StageType.RANK_DEDUCTION, "9", List.of(
                new BusDriverBoard.HintDefinition(11, BusDriverBoard.HintType.GREATER_THAN, "8"),
                new BusDriverBoard.HintDefinition(13, BusDriverBoard.HintType.LESS_THAN, "10"),
                new BusDriverBoard.HintDefinition(15, BusDriverBoard.HintType.GREATER_THAN, "7")
        ));
        assertEquals(List.of("9"), engine.candidates(stage));
        assertTrue(engine.answerCorrect(stage, "9"));
        assertFalse(engine.answerCorrect(stage, "10"));
    }

    @Test void incompleteHintsAreAmbiguous() {
        var stage = new BusDriverBoard.StageDefinition(1, BusDriverBoard.StageType.RANK_DEDUCTION, "9", List.of(
                new BusDriverBoard.HintDefinition(11, BusDriverBoard.HintType.GREATER_THAN, "7")
        ));
        assertTrue(engine.candidates(stage).size() > 1);
    }
}
