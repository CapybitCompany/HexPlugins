package hex.minigames.game;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinigameDefinitionTest {
    @Test
    void minPlayersGateCoversMingleRequirement() {
        MinigameDefinition mingle = new MinigameDefinition(
                "mingle",
                "Mingle",
                true,
                true,
                false,
                8,
                0,
                1,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                10,
                Map.of(),
                "games/mingle.yml"
        );

        assertFalse(mingle.playerCountAllowed(7));
        assertTrue(mingle.playerCountAllowed(8));
    }
}
