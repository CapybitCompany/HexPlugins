package hex.minigames.game.jumprope;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

final class JumpRopeRuntimeTest {
    @Test
    void delayBeginsOnArrivalAndAnotherAttemptCanFinishExactlyOnce() {
        var runtime = new JumpRopeRuntime();
        UUID id = UUID.randomUUID();
        assertTrue(runtime.beginFall(id));
        assertFalse(runtime.beginFall(id));
        assertFalse(runtime.resume(id, 99999));
        assertFalse(runtime.finish(id));
        runtime.arrived(id, 100, 200);
        assertEquals(200, runtime.delayRemaining(id, 100, 200));
        assertFalse(runtime.resume(id, 299));
        assertTrue(runtime.resume(id, 300));
        assertTrue(runtime.finish(id));
        assertFalse(runtime.finish(id));
        assertFalse(runtime.beginFall(id));
    }

    @Test
    void contactCooldownDoesNotHitRespawningOrFinishedPlayers() {
        var runtime = new JumpRopeRuntime();
        UUID id = UUID.randomUUID();
        assertTrue(runtime.canHit(id, 10, 10));
        assertFalse(runtime.canHit(id, 19, 10));
        assertTrue(runtime.canHit(id, 20, 10));
        runtime.beginFall(id);
        assertFalse(runtime.canHit(id, 100, 10));
        runtime.arrived(id, 100, 0);
        assertTrue(runtime.resume(id, 100));
        assertTrue(runtime.canHit(id, 100, 10));
        runtime.finish(id);
        assertFalse(runtime.canHit(id, 200, 10));
    }
}
