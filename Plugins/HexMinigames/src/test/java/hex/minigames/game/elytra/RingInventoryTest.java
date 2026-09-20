package hex.minigames.game.elytra;

import hex.minigames.model.BlockPosition;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RingInventoryTest {
    @Test void reportsMissingExtraAndDuplicateMarkersBeforeChangingTheMap() {
        var actual = new BlockPosition(1, 2, 3);
        var absent = new BlockPosition(4, 5, 6);
        var error = assertThrows(IllegalArgumentException.class,
                () -> RingMaskBuilder.build(Map.of(actual, true), List.of(absent, absent)));
        assertTrue(error.getMessage().contains("na mapie=1"));
        assertTrue(error.getMessage().contains("wpisy ring-order=2"));
        assertTrue(error.getMessage().contains("brak w konfiguracji=[" + actual));
        assertTrue(error.getMessage().contains("brak na mapie=[" + absent));
        assertTrue(error.getMessage().contains("powtorzone wpisy=[" + absent));
    }
}
