package hexnpc.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcItemUseSuppressorTest {

    @Test
    void suppressesUseInsideWindow() {
        AtomicLong now = new AtomicLong(1_000L);
        NpcItemUseSuppressor suppressor = new NpcItemUseSuppressor(Duration.ofNanos(100), now::get);
        UUID playerId = UUID.randomUUID();

        suppressor.suppress(playerId);

        assertTrue(suppressor.shouldCancelUse(playerId));
    }

    @Test
    void expiresAfterWindow() {
        AtomicLong now = new AtomicLong(1_000L);
        NpcItemUseSuppressor suppressor = new NpcItemUseSuppressor(Duration.ofNanos(100), now::get);
        UUID playerId = UUID.randomUUID();

        suppressor.suppress(playerId);
        now.addAndGet(101L);

        assertFalse(suppressor.shouldCancelUse(playerId));
    }

    @Test
    void clearRemovesSuppression() {
        AtomicLong now = new AtomicLong(1_000L);
        NpcItemUseSuppressor suppressor = new NpcItemUseSuppressor(Duration.ofSeconds(1), now::get);
        UUID playerId = UUID.randomUUID();

        suppressor.suppress(playerId);
        suppressor.clear(playerId);

        assertFalse(suppressor.shouldCancelUse(playerId));
    }
}
