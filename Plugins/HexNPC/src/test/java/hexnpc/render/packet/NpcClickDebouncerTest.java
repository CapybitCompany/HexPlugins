package hexnpc.render.packet;

import hexnpc.model.NpcId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcClickDebouncerTest {

    @Test
    void interactAtAndInteractFromSamePhysicalClickExecuteOnce() {
        NpcClickDebouncer debouncer = new NpcClickDebouncer(150L);
        UUID player = UUID.randomUUID();
        NpcId npc = new NpcId("shop1");
        long t0 = 1_000_000_000L;

        assertTrue(debouncer.tryAcquire(player, npc, t0));
        assertFalse(debouncer.tryAcquire(player, npc, t0 + 20_000_000L));
        assertTrue(debouncer.tryAcquire(player, npc, t0 + 151_000_000L));
    }

    @Test
    void differentNpcDoesNotShareDebounceKey() {
        NpcClickDebouncer debouncer = new NpcClickDebouncer(150L);
        UUID player = UUID.randomUUID();
        long t0 = 2_000_000_000L;

        assertTrue(debouncer.tryAcquire(player, new NpcId("shop1"), t0));
        assertTrue(debouncer.tryAcquire(player, new NpcId("shop2"), t0 + 1_000_000L));
    }
}
