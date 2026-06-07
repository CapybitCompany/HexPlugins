package hex.minions.model;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinionInstanceTest {
    @Test
    void storageDoesNotExceedLimitAndDrainResetsCounter() {
        MinionInstance minion = new MinionInstance(
                UUID.randomUUID(), 1L, UUID.randomUUID(), UUID.randomUUID(), "cobblestone", 1,
                new MinionLocation("world", 1, 64, 1, 0), MinionState.ACTIVE,
                100L, 100L, 200L, 0, 3, "default"
        );

        assertEquals(2, minion.addStorage("cobblestone", 2));
        assertEquals(1, minion.addStorage("diamond", 5));
        assertFalse(minion.hasStorageSpace());
        assertEquals(3, minion.storageUsed());

        Map<String, Long> drained = minion.drainStorage();
        assertEquals(2L, drained.get("cobblestone"));
        assertEquals(1L, drained.get("diamond"));
        assertEquals(0, minion.storageUsed());
        assertTrue(minion.hasStorageSpace());
    }
}

