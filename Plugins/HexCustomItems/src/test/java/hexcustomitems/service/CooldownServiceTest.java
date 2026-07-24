package hexcustomitems.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownServiceTest {

    private final UUID player = UUID.randomUUID();
    private final long[] now = {1_000_000L};
    private final CooldownService cooldowns = new CooldownService(() -> now[0]);

    @Test
    void remainingIsZeroWhenUnset() {
        assertEquals(0L, cooldowns.remainingSeconds(player, "jump"));
    }

    @Test
    void applySetsRemainingSeconds() {
        cooldowns.apply(player, "jump", 10);
        assertEquals(10L, cooldowns.remainingSeconds(player, "jump"));
    }

    @Test
    void cooldownExpiresAfterTime() {
        cooldowns.apply(player, "jump", 10);
        now[0] += 10_000L;
        assertEquals(0L, cooldowns.remainingSeconds(player, "jump"));
    }

    @Test
    void zeroSecondsIsIgnored() {
        cooldowns.apply(player, "jump", 0);
        assertEquals(0L, cooldowns.remainingSeconds(player, "jump"));
    }

    @Test
    void clearRemovesCooldowns() {
        cooldowns.apply(player, "jump", 30);
        cooldowns.clear(player);
        assertEquals(0L, cooldowns.remainingSeconds(player, "jump"));
    }

    @Test
    void snapshotFiltersExpiredEntries() {
        cooldowns.apply(player, "expired", 10);
        now[0] += 20_000L;                 // "expired" ist jetzt abgelaufen
        cooldowns.apply(player, "live", 10); // läuft bis now+10s
        Map<UUID, Map<String, Long>> snapshot = cooldowns.snapshot();
        assertTrue(snapshot.containsKey(player));
        assertTrue(snapshot.get(player).containsKey("live"));
        assertFalse(snapshot.get(player).containsKey("expired"));
    }

    @Test
    void loadIgnoresExpiredEntries() {
        Map<String, Long> perItem = new HashMap<>();
        perItem.put("live", now[0] + 10_000L);
        perItem.put("stale", now[0] - 5_000L);
        Map<UUID, Map<String, Long>> data = new HashMap<>();
        data.put(player, perItem);

        cooldowns.load(data);

        assertTrue(cooldowns.remainingSeconds(player, "live") > 0L);
        assertEquals(0L, cooldowns.remainingSeconds(player, "stale"));
    }
}
