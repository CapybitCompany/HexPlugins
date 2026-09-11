package hex.minigames.runtime;

import hex.minigames.game.MinigameDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GameSelectionServiceTest {
    @Test
    void selectionDoesNotDuplicateGames() {
        List<MinigameDefinition> pool = List.of(
                definition("one"),
                definition("two"),
                definition("three")
        );

        List<MinigameDefinition> selected = new GameSelectionService().select(pool, 5);

        assertEquals(3, selected.size());
        assertEquals(3, selected.stream().map(MinigameDefinition::id).distinct().count());
    }

    private MinigameDefinition definition(String id) {
        return new MinigameDefinition(
                id,
                id,
                true,
                true,
                false,
                1,
                0,
                1,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                10,
                Map.of(),
                "test"
        );
    }
}
