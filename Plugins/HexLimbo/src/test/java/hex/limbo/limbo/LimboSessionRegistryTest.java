package hex.limbo.limbo;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LimboSessionRegistryTest {

    @Test
    void addAndRemoveTracksCount() {
        LimboSessionRegistry registry = new LimboSessionRegistry();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        registry.add(new LimboSession(alice, "Alice"));
        assertEquals(1, registry.activeCount());
        registry.add(new LimboSession(bob, "Bob"));
        assertEquals(2, registry.activeCount());
        registry.remove(alice);
        assertEquals(1, registry.activeCount());
        registry.remove(bob);
        assertEquals(0, registry.activeCount());
    }

    @Test
    void sessionStageTransitions() {
        LimboSession session = new LimboSession(UUID.randomUUID(), "Alice");
        assertEquals(LimboSession.Stage.CONNECTING, session.stage());
        session.setStage(LimboSession.Stage.IN_VOID);
        assertEquals(LimboSession.Stage.IN_VOID, session.stage());
        session.setStage(LimboSession.Stage.DISCONNECTED);
        assertEquals(LimboSession.Stage.DISCONNECTED, session.stage());
    }
}
