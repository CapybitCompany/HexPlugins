package hex.minigames.runtime;

import hex.minigames.game.MinigameRegistry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InternalChatBlockTest {
    @Test
    void chatBlockIsInternalAndRemovable() {
        MinigamesSessionService service = new MinigamesSessionService(null, null, null, new MinigameRegistry());
        UUID playerId = UUID.randomUUID();

        service.blockChat(playerId);
        assertTrue(service.chatBlocked(playerId));

        service.unblockChat(playerId);
        assertFalse(service.chatBlocked(playerId));
    }
}
